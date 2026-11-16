"""Media bazasi uchun XAVFSIZ ulanish - Telegramning rasmiy "QR orqali
kirish" oqimi (auth.exportLoginToken / auth.acceptLoginToken) orqali.

Foydalanuvchining ilovadagi JONLI sessiyasiga (auth_key) HECH QACHON
tegilmaydi va u hech qayerga yuborilmaydi. Buning o'rniga:

1. Backend o'zining mutlaqo mustaqil, hali login qilinmagan Telethon
   mijozini yaratadi va standart "QR orqali kirish" (auth.exportLoginToken)
   so'rovini yuboradi - bu xuddi Telegram Desktop/Web ochganda chiqadigan
   QR kod bilan bir xil mexanizm.
2. ParadoksGram ilovasi (allaqachon login qilingan, O'ZINING mavjud
   sessiyasi orqali) shu tokenni auth.acceptLoginToken chaqiruvi bilan
   tasdiqlaydi - bu Telegramning rasmiy, "boshqa qurilmada QR skanerlash"
   funksiyasi bilan AYNAN bir xil, kutilgan xatti-harakat.
3. Shundan keyin backend o'zining TO'LIQ MUSTAQIL auth_key'iga ega
   bo'ladi - bu Sozlamalar -> Qurilmalar ro'yxatida alohida sessiya
   sifatida ko'rinadi va foydalanuvchi istalgan vaqt uni bekor qilishi
   mumkin.

Natijada ikki sessiya (asosiy ilova + backend) bir-biriga mutlaqo
bog'liq emas - Telegramning "bitta auth_key ikki xil ilova sifatida
ishlatilyapti" degan seans-o'g'irlash tekshiruvi endi ishga tushmaydi.

MUHIM (arxitektura eslatmasi): tasdiqlanishni kutish BITTA uzun HTTP
so'rovda emas, FON VAZIFASI (background task) + Android tomonidan tez-tez
QISQA so'rovlar (polling) orqali amalga oshiriladi. Avvalgi versiyada
/sessions/qr/finish kutish tugamaguncha (20 soniyagacha) HTTP javobini
ushlab turar edi - bu Android'ning o'zining readTimeout'idan (15s) uzunroq
bo'lib, deyarli har doim mijoz tomonida oldinroq timeout berar edi va
funksiya "sekin ishlayapti/ishlamayapti" bo'lib ko'rinar edi.
"""

import asyncio
import base64
import datetime
import logging
import time
from typing import Dict, Optional

from telethon import TelegramClient
from telethon.errors import SessionPasswordNeededError
from telethon.sessions import StringSession

import config
import session_store

log = logging.getLogger("qr_login")

_PENDING_TTL_SECONDS = 180  # shundan ortiq kutib turgan pending yozuvlar tozalanadi


class _Pending:
    __slots__ = ("client", "qr", "target_chat_id", "created_at", "status", "message", "task", "saved_session_string")

    def __init__(self, client, qr, target_chat_id):
        self.client = client
        self.qr = qr
        self.target_chat_id = target_chat_id
        self.created_at = time.monotonic()
        self.status = "waiting"  # waiting | password_required | connected | error
        self.message: Optional[str] = None
        self.task: Optional[asyncio.Task] = None
        self.saved_session_string: Optional[str] = None


_pending: Dict[str, _Pending] = {}


async def _disconnect_quiet(client) -> None:
    try:
        if client.is_connected():
            await client.disconnect()
    except Exception:
        log.exception("mijozni uzishda xatolik")


async def _drop(user_key: str) -> None:
    p = _pending.pop(user_key, None)
    if p is not None:
        if p.task is not None:
            p.task.cancel()
        await _disconnect_quiet(p.client)


async def _cleanup_stale() -> None:
    now = time.monotonic()
    stale = [k for k, v in _pending.items() if now - v.created_at > _PENDING_TTL_SECONDS]
    for k in stale:
        await _drop(k)


async def start(user_key: str, target_chat_id: Optional[int]) -> dict:
    """Yangi, mustaqil mijoz yaratadi va QR-login tokenini qaytaradi.
    Tasdiqlanishni FON VAZIFASI kutadi - bu chaqiruv darhol qaytadi."""
    await _cleanup_stale()
    await _drop(user_key)

    client = TelegramClient(StringSession(), config.API_ID, config.API_HASH)
    await client.connect()
    qr = await client.qr_login()
    pending = _Pending(client, qr, target_chat_id)
    _pending[user_key] = pending
    pending.task = asyncio.create_task(_watch(user_key))

    expires_in = (qr.expires - datetime.datetime.now(tz=datetime.timezone.utc)).total_seconds()
    return {
        "status": "ok",
        # standart (paddingli) base64url - Android tomonida Base64.URL_SAFE bilan o'qiladi
        "token": base64.urlsafe_b64encode(qr.token).decode("ascii"),
        "expires_in": max(1, int(expires_in)),
    }


async def _watch(user_key: str) -> None:
    """Fon vazifasi: tasdiqlanishini (yoki muddati tugashini) kutadi va
    pending.status'ni yangilaydi - Android buni poll() orqali so'raydi."""
    pending = _pending.get(user_key)
    if pending is None:
        return
    try:
        await asyncio.wait_for(pending.qr.wait(), timeout=_PENDING_TTL_SECONDS)
    except SessionPasswordNeededError:
        pending.status = "password_required"
        return
    except asyncio.CancelledError:
        return
    except asyncio.TimeoutError:
        pending.status = "error"
        pending.message = "Tasdiqlash vaqti tugadi. Qaytadan urinib ko'ring."
        return
    except Exception as e:
        log.exception("qr watch failed for %s", user_key)
        pending.status = "error"
        pending.message = str(e)
        return

    await _complete(user_key, pending)


async def poll(user_key: str, manager) -> dict:
    """Android bu funksiyani 1-2 soniyada bir chaqirib, joriy holatni
    so'raydi. Hech qachon uzoq bloklamaydi - darhol qaytadi."""
    pending = _pending.get(user_key)
    if pending is None:
        return {"status": "error", "message": "Avval QR so'rovi boshlanmagan yoki muddati tugagan. Qaytadan urinib ko'ring."}

    if pending.status == "connected":
        # forwarder'ni ishga tushirish BIROZ vaqt olishi mumkin - buni
        # fon vazifasiga qoldiramiz, shunda bu poll chaqiruvi darhol
        # qaytadi va Android tomonida yana timeout bo'lib qolmaydi.
        _pending.pop(user_key, None)
        asyncio.create_task(_finalize_forwarder(user_key, pending, manager))
        return {"status": "connected"}
    if pending.status == "error":
        message = pending.message or "Nomaʼlum xatolik"
        await _drop(user_key)
        return {"status": "error", "message": message}
    # waiting | password_required
    return {"status": pending.status}


async def submit_password(user_key: str, password: str, manager) -> dict:
    """Agar akkountda bulutli parol (2FA) yoqilgan bo'lsa, poll()
    "password_required" qaytargandan keyin shu chaqiriladi. Natija ham
    poll() orqali kuzatiladi (status darhol "waiting"ga qaytariladi)."""
    pending = _pending.get(user_key)
    if pending is None:
        return {"status": "error", "message": "Avval QR so'rovi boshlanmagan yoki muddati tugagan. Qaytadan urinib ko'ring."}
    try:
        await pending.client.sign_in(password=password)
    except Exception as e:
        log.exception("qr password sign-in failed for %s", user_key)
        return {"status": "error", "message": "Parol noto'g'ri yoki xatolik yuz berdi: " + str(e)}

    await _complete(user_key, pending)
    return {"status": "ok"}


async def _complete(user_key: str, pending: "_Pending") -> None:
    """Login muvaffaqiyatli bo'lgach chaqiriladi - sessiya satrini olib,
    pending.status'ni "connected" qiladi. Forwarder'ni ishga tushirish
    poll() ichida (manager mavjud bo'lganda) amalga oshiriladi."""
    try:
        session_string = pending.client.session.save()
    except Exception as e:
        log.exception("session.save() failed for %s", user_key)
        pending.status = "error"
        pending.message = str(e)
        return
    pending.saved_session_string = session_string
    pending.status = "connected"


async def _finalize_forwarder(user_key: str, pending: "_Pending", manager) -> None:
    session_string = pending.saved_session_string
    if session_string is None:
        return
    await _disconnect_quiet(pending.client)
    try:
        await manager.start_forwarder(user_key, session_string, pending.target_chat_id)
        session_store.save_session(user_key, session_string, pending.target_chat_id)
    except Exception:
        log.exception("failed to start forwarder after qr login for %s", user_key)

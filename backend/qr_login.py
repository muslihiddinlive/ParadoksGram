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
    __slots__ = ("client", "qr", "target_chat_id", "created_at")

    def __init__(self, client, qr, target_chat_id):
        self.client = client
        self.qr = qr
        self.target_chat_id = target_chat_id
        self.created_at = time.monotonic()


_pending: Dict[str, _Pending] = {}


async def _drop(user_key: str) -> None:
    p = _pending.pop(user_key, None)
    if p is not None:
        try:
            if p.client.is_connected():
                await p.client.disconnect()
        except Exception:
            log.exception("pending mijozni uzishda xatolik (%s)", user_key)


async def _cleanup_stale() -> None:
    now = time.monotonic()
    stale = [k for k, v in _pending.items() if now - v.created_at > _PENDING_TTL_SECONDS]
    for k in stale:
        await _drop(k)


async def start(user_key: str, target_chat_id: Optional[int]) -> dict:
    """Yangi, mustaqil mijoz yaratadi va QR-login tokenini qaytaradi."""
    await _cleanup_stale()
    await _drop(user_key)

    client = TelegramClient(StringSession(), config.API_ID, config.API_HASH)
    await client.connect()
    qr = await client.qr_login()
    _pending[user_key] = _Pending(client, qr, target_chat_id)

    expires_in = (qr.expires - datetime.datetime.now(tz=datetime.timezone.utc)).total_seconds()
    return {
        "status": "ok",
        # standart (paddingli) base64url - Android tomonida Base64.URL_SAFE bilan o'qiladi
        "token": base64.urlsafe_b64encode(qr.token).decode("ascii"),
        "expires_in": max(1, int(expires_in)),
    }


async def finish(user_key: str, manager, timeout: float = 20.0) -> dict:
    """Android ilovasi auth.acceptLoginToken'ni chaqirganidan KEYIN
    chaqiriladi - tasdiqlanishini kutadi va muvaffaqiyatli bo'lsa
    sessiyani saqlab, forwarder'ni ishga tushiradi."""
    pending = _pending.get(user_key)
    if pending is None:
        return {"status": "error", "message": "Avval QR so'rovi boshlanmagan yoki muddati tugagan. Qaytadan urinib ko'ring."}

    try:
        await asyncio.wait_for(pending.qr.wait(), timeout=timeout)
    except SessionPasswordNeededError:
        return {"status": "password_required"}
    except asyncio.TimeoutError:
        await _drop(user_key)
        return {"status": "error", "message": "Tasdiqlash vaqti tugadi. Qaytadan urinib ko'ring."}
    except Exception as e:
        await _drop(user_key)
        log.exception("qr finish failed for %s", user_key)
        return {"status": "error", "message": str(e)}

    return await _complete(user_key, manager)


async def submit_password(user_key: str, password: str, manager) -> dict:
    """Agar akkountda bulutli parol (2FA) yoqilgan bo'lsa, `finish`
    "password_required" qaytargandan keyin shu chaqiriladi."""
    pending = _pending.get(user_key)
    if pending is None:
        return {"status": "error", "message": "Avval QR so'rovi boshlanmagan yoki muddati tugagan. Qaytadan urinib ko'ring."}
    try:
        await pending.client.sign_in(password=password)
    except Exception as e:
        log.exception("qr password sign-in failed for %s", user_key)
        return {"status": "error", "message": "Parol noto'g'ri yoki xatolik yuz berdi: " + str(e)}
    return await _complete(user_key, manager)


async def _complete(user_key: str, manager) -> dict:
    pending = _pending.pop(user_key, None)
    if pending is None:
        return {"status": "error", "message": "Ichki xatolik: pending topilmadi"}

    session_string = pending.client.session.save()
    target_chat_id = pending.target_chat_id
    try:
        await pending.client.disconnect()
    except Exception:
        pass

    try:
        await manager.start_forwarder(user_key, session_string, target_chat_id)
        session_store.save_session(user_key, session_string, target_chat_id)
    except Exception as e:
        log.exception("failed to start forwarder after qr login for %s", user_key)
        return {"status": "error", "message": "Sessiya olindi, lekin ulanishda xatolik: " + str(e)}
    return {"status": "connected"}

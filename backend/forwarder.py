"""Bitta foydalanuvchining Telegram sessiyasi: yangi media xabarlarni
avtomatik forward qiladi va shu bilan birga akkauntning "online"/"last seen"
holatini yashirib turadi.
"""

import asyncio
import logging
from collections import OrderedDict
from typing import Optional, Tuple

from telethon import TelegramClient, events
from telethon.sessions import StringSession
from telethon.tl.functions.account import UpdateStatusRequest

import config
import copy_store

log = logging.getLogger("forwarder")

# PARADOKS: foydalanuvchi aytganidek - tahrirlangan xabarlar qurilma o'chiq
# bo'lsa ham sezilishi kerak. Qurilmadagi (Android/Windows) forward faqat
# ilova ochiq paytda ishlaydi; bu yerda esa akkauntning serverdagi sessiyasi
# 24/7 ulangan bo'lgani uchun, tahrirlashni qurilmadan mutlaqo mustaqil
# ravishda ushlab, backup chatga yuboradi.
MAX_TEXT_CACHE = 2000


class UserForwarder:
    def __init__(self, user_key: str, session_string: str, target_chat_id: Optional[int]):
        self.user_key = user_key
        self.target_chat_id = target_chat_id
        self.client = TelegramClient(StringSession(session_string), config.API_ID, config.API_HASH)
        self._status_task: Optional[asyncio.Task] = None
        # (chat_id, msg_id) -> oxirgi ko'rilgan matn. Faqat shu sessiya ulangan
        # paytda ko'rilgan xabarlar uchun bo'ladi - shuning uchun "oldin"
        # matnni bilmasak (masalan server hozirgina ulangan bo'lsa), hech
        # qanday taxmin qilmasdan shunchaki tahrirlash haqida xabar bermaymiz.
        self._text_cache: "OrderedDict[Tuple[int, int], str]" = OrderedDict()

    async def start(self) -> None:
        # client.start() interaktiv login oqimi uchun mo'ljallangan (kerak bo'lsa
        # telefon raqami/kod so'raydi) - serverda bu yoki osilib qoladi, yoki xato
        # beradi. Bizda sessiya allaqachon tayyor (dc_id+auth_key), shuning uchun
        # faqat ulanamiz va avtorizatsiyani tekshiramiz, hech qachon login so'ramaymiz.
        await self.client.connect()
        if not await self.client.is_user_authorized():
            await self.client.disconnect()
            raise RuntimeError("Sessiya haqiqiy emas yoki eskirgan (is_user_authorized=False)")
        self.client.add_event_handler(self._on_message, events.NewMessage(incoming=True))
        self.client.add_event_handler(self._on_edit, events.MessageEdited(incoming=True))
        self._status_task = asyncio.create_task(self._stay_invisible())
        log.info("forwarder started for %s", self.user_key)

    async def stop(self) -> None:
        if self._status_task:
            self._status_task.cancel()
        if self.client.is_connected():
            await self.client.disconnect()
        log.info("forwarder stopped for %s", self.user_key)

    def set_target(self, target_chat_id: int) -> None:
        self.target_chat_id = target_chat_id

    async def _stay_invisible(self) -> None:
        # MTProto ulanishi o'zi "online" belgisini qo'ymaydi, lekin ba'zi
        # chaqiriqlar statusni qayta tiklab qo'yishi mumkin - shuning uchun
        # offline holatni davriy ravishda majburlab turamiz.
        while True:
            try:
                await self.client(UpdateStatusRequest(offline=True))
            except Exception:
                log.exception("offline status refresh failed for %s", self.user_key)
            await asyncio.sleep(config.STATUS_REFRESH_SECONDS)

    async def _on_message(self, event) -> None:
        # PARADOKS: ilgari faqat mediali xabarlar forward qilinardi
        # ("if not event.message.media: return"). Endi - foydalanuvchi
        # so'ragani kabi - MATNLI xabarlar ham saqlanadi, chunki qurilma
        # o'chiq bo'lsa, ularni ham hech kim boshqa joyda ko'rmaydi va
        # asl chatdan o'chirilsa, butunlay yo'qoladi. Bo'sh xizmat
        # xabarlarini (masalan "X guruhga qo'shildi") forward qilishning
        # ma'nosi yo'q, shuning uchun shunday holatlarni tashlab o'tamiz.
        self._remember_text(event.chat_id, event.message.id, event.message.message)
        if not self.target_chat_id:
            return
        if event.message.action is not None:
            return
        try:
            result = await self.client.forward_messages(self.target_chat_id, event.message)
        except Exception:
            log.exception("forward failed for %s", self.user_key)
            return

        # Natija bitta Message yoki ro'yxat bo'lishi mumkin - moslashtirish
        # xaritasiga yozish uchun bitta xabarga keltiramiz.
        copy_message = result[0] if isinstance(result, list) else result
        if copy_message is None:
            return
        try:
            copy_store.record_copy(
                self.user_key,
                source_chat_id=event.chat_id,
                source_msg_id=event.message.id,
                target_chat_id=self.target_chat_id,
                copy_msg_id=copy_message.id,
            )
        except Exception:
            log.exception(
                "copy_store.record_copy failed for %s (%s/%s)",
                self.user_key,
                event.chat_id,
                event.message.id,
            )

    def _remember_text(self, chat_id: int, msg_id: int, text: Optional[str]) -> None:
        key = (chat_id, msg_id)
        self._text_cache[key] = text or ""
        self._text_cache.move_to_end(key)
        while len(self._text_cache) > MAX_TEXT_CACHE:
            self._text_cache.popitem(last=False)

    async def _on_edit(self, event) -> None:
        # Qurilma (Android/Windows) faqat o'zi ochiq paytdagi tahrirlashni
        # ko'radi. Bu yerda esa serverdagi sessiya doim ulangan bo'lgani
        # uchun - foydalanuvchining qurilmasi o'chiq bo'lsa ham - tahrirlash
        # aynan shu orqali ushlanib, backup chatga yuboriladi.
        key = (event.chat_id, event.message.id)
        before = self._text_cache.get(key)
        after = event.message.message or ""
        self._remember_text(event.chat_id, event.message.id, after)
        if before is None or before == after:
            # Eski matnni bilmaymiz (server hali ulanmagan payt yozilgan
            # xabar bo'lishi mumkin) yoki ko'rinadigan o'zgarish yo'q.
            return
        if not self.target_chat_id or event.chat_id == self.target_chat_id:
            return
        if event.message.action is not None:
            return
        try:
            chat = await event.get_chat()
            chat_title = (
                getattr(chat, "title", None)
                or getattr(chat, "first_name", None)
                or str(event.chat_id)
            )
            text = (
                "✏️ Tahrirlangan xabar ({title})\n\n"
                "Eski matn:\n{before}\n\n"
                "Yangi matn:\n{after}"
            ).format(
                title=chat_title,
                before=before or "(matnsiz)",
                after=after or "(matnsiz)",
            )
            await self.client.send_message(self.target_chat_id, text, silent=True)
        except Exception:
            log.exception(
                "edit notice failed for %s (%s/%s)",
                self.user_key,
                event.chat_id,
                event.message.id,
            )

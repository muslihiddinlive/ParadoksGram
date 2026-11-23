"""Bitta foydalanuvchining Telegram sessiyasi: yangi media xabarlarni
avtomatik forward qiladi va shu bilan birga akkauntning "online"/"last seen"
holatini yashirib turadi.
"""

import asyncio
import logging
from typing import Optional

from telethon import TelegramClient, events
from telethon.sessions import StringSession
from telethon.tl.functions.account import UpdateStatusRequest

import config
import copy_store

log = logging.getLogger("forwarder")


class UserForwarder:
    def __init__(self, user_key: str, session_string: str, target_chat_id: Optional[int]):
        self.user_key = user_key
        self.target_chat_id = target_chat_id
        self.client = TelegramClient(StringSession(session_string), config.API_ID, config.API_HASH)
        self._status_task: Optional[asyncio.Task] = None

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

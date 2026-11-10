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

log = logging.getLogger("forwarder")


class UserForwarder:
    def __init__(self, user_key: str, session_string: str, target_chat_id: Optional[int]):
        self.user_key = user_key
        self.target_chat_id = target_chat_id
        self.client = TelegramClient(StringSession(session_string), config.API_ID, config.API_HASH)
        self._status_task: Optional[asyncio.Task] = None

    async def start(self) -> None:
        await self.client.start()
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
        if not event.message.media:
            return
        if not self.target_chat_id:
            return
        try:
            await self.client.forward_messages(self.target_chat_id, event.message)
        except Exception:
            log.exception("forward failed for %s", self.user_key)

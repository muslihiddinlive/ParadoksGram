"""Barcha ulangan foydalanuvchilarning forwarder'larini boshqaradi:
server qayta ishga tushganda mavjud sessiyalarni tiklaydi, yangi
ulanishlarni ishga tushiradi/to'xtatadi.
"""

import logging
from typing import Dict

import config
import session_store
from forwarder import UserForwarder

log = logging.getLogger("manager")


class SessionManager:
    def __init__(self) -> None:
        self._forwarders: Dict[str, UserForwarder] = {}

    async def restore_all(self) -> None:
        # ESKATMA: bu yerda MEDIA_BACKUP_DISABLED tekshirilmaydi - u faqat
        # eski, xavfsiz bo'lmagan /sessions/register endpointiga tegishli.
        # Endi saqlanadigan barcha sessiyalar xavfsiz QR-login oqimi orqali
        # olinadi (qr_login.py), shuning uchun tiklash xavfsiz.
        for user_key in session_store.list_user_keys():
            session_string, target_chat_id = session_store.get_session(user_key)
            if session_string:
                try:
                    await self.start_forwarder(user_key, session_string, target_chat_id)
                except Exception:
                    log.exception("failed to restore session for %s", user_key)

    async def start_forwarder(self, user_key: str, session_string: str, target_chat_id) -> None:
        existing = self._forwarders.get(user_key)
        if existing:
            await existing.stop()
        fw = UserForwarder(user_key, session_string, target_chat_id)
        await fw.start()
        self._forwarders[user_key] = fw

    async def stop_forwarder(self, user_key: str) -> None:
        fw = self._forwarders.pop(user_key, None)
        if fw:
            await fw.stop()

    def set_target(self, user_key: str, target_chat_id: int) -> None:
        session_store.set_target(user_key, target_chat_id)
        fw = self._forwarders.get(user_key)
        if fw:
            fw.set_target(target_chat_id)

    def is_connected(self, user_key: str) -> bool:
        return user_key in self._forwarders

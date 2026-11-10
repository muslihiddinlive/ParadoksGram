"""Telefon raqam orqali login oqimi (kod yuborish / tasdiqlash).

Oqim:
  1. POST /sessions/start {"phone": "+998..."}  -> SMS/Telegram kod yuboradi
  2. POST /sessions/verify {"phone", "code", "phone_code_hash", "password"?}
     -> sessiya yaratadi, forward uchun ishga tushiradi
"""

import logging
from typing import Dict, Optional

from telethon import TelegramClient
from telethon.errors import SessionPasswordNeededError
from telethon.sessions import StringSession

import config

log = logging.getLogger("auth")

# Login jarayoni tugamagan mijozlar: telefon -> TelegramClient
_pending: Dict[str, TelegramClient] = {}


async def send_code(phone: str) -> str:
    client = TelegramClient(StringSession(), config.API_ID, config.API_HASH)
    await client.connect()
    sent = await client.send_code_request(phone)
    _pending[phone] = client
    return sent.phone_code_hash


async def verify_code(
    phone: str, code: str, phone_code_hash: str, password: Optional[str] = None
) -> str:
    client = _pending.get(phone)
    if client is None:
        raise RuntimeError("Avval /sessions/start chaqirilishi kerak")

    try:
        await client.sign_in(phone=phone, code=code, phone_code_hash=phone_code_hash)
    except SessionPasswordNeededError:
        if not password:
            raise
        await client.sign_in(password=password)

    session_string = client.session.save()
    await client.disconnect()
    _pending.pop(phone, None)
    return session_string


def cancel_pending(phone: str) -> None:
    client = _pending.pop(phone, None)
    if client:
        log.info("cancelled pending login for %s", phone)

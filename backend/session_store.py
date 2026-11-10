"""Foydalanuvchi sessiyalarini (shifrlangan holda) diskda saqlash.

Har bir yozuv: {"<phone>": {"session": "<fernet-encrypted-string>",
                             "target_chat_id": <int|null>}}
"""

import json
import os
import threading
from typing import Optional, Tuple

from cryptography.fernet import Fernet

import config

_lock = threading.Lock()
_fernet = Fernet(config.ENCRYPT_KEY.encode())


def _load_raw() -> dict:
    if not os.path.exists(config.SESSIONS_FILE):
        return {}
    with open(config.SESSIONS_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def _save_raw(data: dict) -> None:
    tmp_path = config.SESSIONS_FILE + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp_path, config.SESSIONS_FILE)


def list_user_keys() -> list:
    with _lock:
        return list(_load_raw().keys())


def save_session(user_key: str, session_string: str, target_chat_id: Optional[int] = None) -> None:
    with _lock:
        data = _load_raw()
        entry = data.get(user_key, {})
        entry["session"] = _fernet.encrypt(session_string.encode()).decode()
        if target_chat_id is not None:
            entry["target_chat_id"] = target_chat_id
        data[user_key] = entry
        _save_raw(data)


def set_target(user_key: str, target_chat_id: int) -> None:
    with _lock:
        data = _load_raw()
        if user_key not in data:
            raise KeyError(user_key)
        data[user_key]["target_chat_id"] = target_chat_id
        _save_raw(data)


def get_session(user_key: str) -> Tuple[Optional[str], Optional[int]]:
    with _lock:
        data = _load_raw()
        entry = data.get(user_key)
        if not entry:
            return None, None
        session_string = _fernet.decrypt(entry["session"].encode()).decode()
        return session_string, entry.get("target_chat_id")


def delete_session(user_key: str) -> None:
    with _lock:
        data = _load_raw()
        data.pop(user_key, None)
        _save_raw(data)

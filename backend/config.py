import os

# Telegram API credentials (my.telegram.org dan olinadi)
API_ID = int(os.environ["TG_API_ID"])
API_HASH = os.environ["TG_API_HASH"]

# TG_API_ID doim 32-bitli butun son bo'lishi kerak (my.telegram.org'dagi "App api_id").
# Agar bu qiymat noto'g'ri joylashtirilgan bo'lsa (masalan API_HASH bilan aralashtirilsa
# yoki ortiqcha raqam qo'shilib ketsa), Telethon buni struct.error sifatida juda
# tushunarsiz chiqaradi ("'i' format requires ..."). Shu yerda darhol aniq xato beramiz.
if not (-2147483648 <= API_ID <= 2147483647):
    raise RuntimeError(
        "TG_API_ID noto'g'ri: qiymati 32-bitli butun son chegarasidan tashqarida "
        f"({API_ID}). Render muhit o'zgaruvchilarida TG_API_ID ni my.telegram.org'dagi "
        "'App api_id' qiymati bilan almashtiring (faqat raqamlar, API_HASH emas)."
    )

# Sessiya satrlarini shifrlash uchun kalit.
# Generatsiya qilish: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
ENCRYPT_KEY = os.environ["SESSION_ENCRYPT_KEY"]

# Render "PORT" muhit o'zgaruvchisini o'zi beradi
PORT = int(os.environ.get("PORT", 8080))

# Sessiyalar saqlanadigan fayl (DIQQAT: Render free diskida restart/redeploy'da
# bu fayl o'chib ketadi - pastdagi README'dagi eslatmaga qarang)
SESSIONS_FILE = os.environ.get("SESSIONS_FILE", "sessions.json")

# "Manba xabar -> backup nusxa" moslashtirish xaritasi saqlanadigan fayl
# (copy_store.py). Xuddi SESSIONS_FILE kabi Render bepul diskida vaqtinchalik.
COPIES_FILE = os.environ.get("COPIES_FILE", "copies.json")

# Har bir foydalanuvchi uchun saqlanadigan moslashtirish yozuvlarining
# yuqori chegarasi (eskisi tashlab yuboriladi) - faylning cheksiz
# o'sib ketishining oldini oladi.
MAX_COPIES_PER_USER = int(os.environ.get("MAX_COPIES_PER_USER", "20000"))

# Har necha soniyada "offline" statusni qayta tasdiqlab turish kerak
STATUS_REFRESH_SECONDS = int(os.environ.get("STATUS_REFRESH_SECONDS", "50"))

# XAVFSIZLIK: bu faqat ESKI /sessions/register endpointiga tegishli -
# u foydalanuvchining JONLI auth_key'ini shu backend'ga nusxalab, boshqa
# api_id/api_hash bilan PARALEL ulanar edi. Telegram buni amalda "sessiya
# o'g'irlash" urinishi deb baholab, o'sha auth_key'ni butunlay bekor qilib
# qo'ygani (foydalanuvchi ParadoksGram'dan chiqarib yuborilishi, Secret
# Chat'lari yo'qolishi bilan) kuzatildi - shu sabab bu ESKI yo'l doimiy
# o'chirilgan. Yangi, xavfsiz oqim (qr_login.py, auth.exportLoginToken/
# auth.acceptLoginToken orqali) bu bayroqqa bog'liq emas va doim ishlaydi.
MEDIA_BACKUP_DISABLED = os.environ.get("MEDIA_BACKUP_ENABLE_UNSAFE") != "1"

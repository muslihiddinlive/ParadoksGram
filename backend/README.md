# ParadoksGram Media Backend

Foydalanuvchi sozlamalardan "media bazasi"ni ulaganda va forward
toggle'ini ikki marta tasdiqlaganda ishga tushadigan server: foydalanuvchi
nomidan (userbot sifatida) kiradigan Telegram sessiyasi orqali yangi media
xabarlarni avtomatik forward qiladi, qurilma o'chiq bo'lsa ham. Shu bilan
birga akkauntning "online"/"last seen" holatini yashirib turadi.

## Render.com'da deploy qilish

1. Render'da **New > Web Service** yarating, shu repo va
   `feature/media-backend` branch'ni tanlang.
2. **Root Directory**: `backend`
3. **Build Command**: `pip install -r requirements.txt`
4. **Start Command**: `python main.py`
5. Muhit o'zgaruvchilarini (Environment) qo'shing:
   - `TG_API_ID` va `TG_API_HASH` — https://my.telegram.org dan oling
   - `SESSION_ENCRYPT_KEY` — quyidagi buyruq bilan generatsiya qiling:
     ```
     python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
     ```
   - `PORT` ni qo'lda qo'yish shart emas, Render o'zi beradi.
6. Deploy tugagach, service URL'ingiz tayyor bo'ladi (masalan
   `https://sizning-app.onrender.com`).

## UptimeRobot ulash

UptimeRobot'da **HTTP(s)** turidagi monitor yarating:
- URL: `https://sizning-app.onrender.com/`
- Interval: 5 daqiqa

Bu Render'ning bo'sh (free) instansiyasini "sleep" holatiga ketishidan
saqlaydi.

> **Eslatma:** UptimeRobot faqat "sleep"ni to'xtatadi. Render free
> tarifida umumiy oylik ishlash soati chegarasi ham bo'lishi mumkin — buni
> Render dashboard'dan joriy holatiga qarab tekshiring, chunki shartlar
> vaqti-vaqti bilan o'zgaradi.

## API

- `GET /` — health check (UptimeRobot shu yerga ping qiladi)
- `POST /sessions/start` `{"phone": "+998901234567"}` — login kodini
  yuboradi, javobda `phone_code_hash` qaytadi
- `POST /sessions/verify` `{"phone", "code", "phone_code_hash", "password"?, "target_chat_id"?}`
  — kodni tasdiqlaydi, sessiyani saqlaydi va forward'ni ishga tushiradi
- `POST /sessions/{phone}/target` `{"target_chat_id": -100...}` — forward
  manzilini o'zgartiradi
- `GET /sessions/{phone}/status` — ulanganmi yo'qmi
- `POST /sessions/{phone}/disconnect` — forward'ni to'xtatadi va
  sessiyani o'chiradi (ilovadagi toggle o'chirilganda chaqiriladi)

## Muhim cheklov: sessiyalar saqlanishi

Sessiya satrlari (`sessions.json`) shifrlangan holda local diskda
saqlanadi. Render'ning **free** Web Service'larida disk doimiy emas —
har bir redeploy yoki uzoq muddatli qayta ishga tushishda bu fayl
o'chib ketishi va foydalanuvchilar qayta ulanishi kerak bo'lishi mumkin.
Productionga chiqishdan oldin buni hal qilish variantlari:

- Render'ning pullik tarifidagi **Persistent Disk** qo'shish, yoki
- Sessiyalarni tashqi bazada (masalan sizning boshqa botlaringizdagi
  kabi, alohida Telegram kanalida shifrlangan xabar sifatida yoki
  haqiqiy DB'da) saqlash.

## Xavfsizlik

- `SESSION_ENCRYPT_KEY`ni hech qachon repo'ga commit qilmang — faqat
  Render Environment Variables orqali bering.
- `TG_API_ID`/`TG_API_HASH` ham shu tarzda maxfiy saqlanadi.
- `sessions.json` `.gitignore`'da — tasodifan commit qilinmaydi.

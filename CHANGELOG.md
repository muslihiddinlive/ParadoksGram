# ParadoksGram o'zgarishlar tarixi

Versiya formati Telegram kabi: `MAJOR.MINOR.PATCH`, qavs ichida build raqami (masalan `3.5.0 (87)`).

- **MAJOR (X.0.0):** ilovaning yuzi yoki asosi o'zgardi.
- **MINOR (1.X.0):** yangi funksiya qo'shildi.
- **PATCH (1.1.X):** faqat xato tuzatildi.

Versiya raqami `VERSION` faylida turadi. Har versiya uchun foydalanuvchiga ko'rinadigan matn `changelog/<versiya>.md` fayliga yoziladi: CI shuni reliz matniga qo'yadi, ilova yangilanish oynasida shuni ko'rsatadi. Commit xabari reliz matniga tushmaydi.

## 3.35.1 (9-noyabr)
Liquid glass sinov maydoni foni: faqat sharpa logosi

## 3.35.0 (9-noyabr)
Liquid glass: yangi ko'rinish, rollar (yuborilgan, kelgan, o'chirilgan), tugmalar uchun shisha

## 3.34.0 (8-noyabr)
Mini-app klonlash: o'z yoki soxta initData bilan

## 3.33.0 (8-noyabr)
Paradoks AI olib tashlandi, shisha sinov maydoni foniga logo qo'yildi

## 3.32.0 (8-noyabr)
Liquid glass: jonli sinov maydoni va yaxshilangan sinish effekti

## 3.31.1 (8-noyabr)
O'chirilgan media: baza nusxasidan yuklash tuzatildi, oldindan saqlash endi standart o'chiq

## 3.31.0 (7-noyabr)
Rasm metadatasini (EXIF) ko'rish
Yangi:
- Rasm yoki rasm fayli xabarining menyusida "Metadata (EXIF) ni ko'rish": qurilma modeli, sana, dastur, ekspozitsiya, ISO va GPS joylashuv (xaritada ochish tugmasi bilan). Telegram oddiy rasm sifatida yuborilganda metadatani o'chiradi, shuning uchun u asosan fayl sifatida yuborilgan rasmlarda ko'rinadi.

## 3.30.0 (7-noyabr)
Lotin↔Kirill, AI vositalari, yuklashlar ekrani, proksi lentasi
Yangi:
- Lotin ↔ Kirill o'girish: xabar menyusida va yozish maydonida matnni belgilab.
- Xabar menyusida "AI bilan tarjima" va "AI bilan tushuntirish", chat menyusida "AI bilan qisqartirish".
- Yuklashlar ekrani (sozlamalarda), qo'ng'iroq oldidan so'rash, proksi ro'yxatidan avto-ulanish va Minecraft server holati.
- Xavfsizlik: AI va reklama serveri faqat HTTPS, AI amallarini telefonda tasdiqlash.

## 3.29.0 (7-noyabr)
Musiqiy ijodxona

Yangi:
- Musiqa pleyerining "⋮" menyusida "Thumbnailni yuklab olish" tugmasi: ijro etilayotgan musiqaning muqova rasmini galereyaga saqlaydi.
- Musiqa pleyerining "⋮" menyusida "Musiqiy ijodxona" tugmasi: lokal ishlaydigan (mini-app emas) musiqa studiyasi. Effektlar (karaoke, bass, equalizer, 8D, echo, reverb, slow, nightcore), spektr vizualizatori, A-B takror, BPM, WAV/M4A yuklab olish, vizualizatorli video yaratish va ulashish.

## 3.28.0 (7-noyabr)
O'chirilgan xabar mediasi yuklash tugmasi bilan bazadan olinadi

## 3.27.0 (7-noyabr)
Kelgan xabarlarga ham liquid glass, media oldindan saqlash sozlamalari

## 3.26.0 (7-noyabr)
Liquid glass: rangsiz, sozlanadigan

## 3.25.0 (7-noyabr)
Xabar orqa foni: liquid glass

## 3.24.0 (7-noyabr)
Ikonka tanlash oynasidagi ko'rinish tuzatildi

## 3.23.1 (6-noyabr)
Yopiq guruhlarni ham klonlash mumkin
O'zgardi:
- Chatni klon qilish endi yopiq (havolasiz) guruh va kanallarda ham chiqadi. Saqlash va uzatish cheklangan chatlar oldingidek klonlanmaydi.

## 3.23.0 (6-noyabr)
Xabar vaqti soniyalar bilan, a'zolar soni aniq
Yangi:
- Xabar pufagidagi vaqt endi soniyalar bilan ko'rinadi (masalan 11:52:37).
- Kanal va guruh sarlavhasida hamda profilda obunachilar/a'zolar soni qisqartirilmay (12 345) ko'rinadi.

## 3.21.0 (6-noyabr)
Ghost mode: online holati qayta-qayta oflayn qilinadi

## 3.20.0 (6-noyabr)
Mini-appni bitta HTML qilib klonlash

## 3.19.0 (6-noyabr)
Yuklash rejimi: oddiy, sekin internet, avto

## 3.18.0 (6-noyabr)
Yuborilgan rasmlardan metadata avtomatik tozalanadi

## 3.17.0 (6-noyabr)
Mini-app menyusida "Mini-appni klonlash (ZIP)" tugmasi

## 3.16.1 (6-noyabr)
Eski telefonlarga o'rnatish tuzatildi (Samsung A03 kabi 32-bit)
Tuzatildi:
- Ilova endi 32-bitli (armeabi-v7a) telefonlarga ham o'rnatiladi. Oldin faqat 64-bitli telefonlarga tushardi.

## 3.16.0 (6-noyabr)
Asos Telegram Android 13.1.1 ga yangilandi

## 3.15.1 (6-noyabr)
Mini-app nusxasi Saved Messages ga
O'zgardi:
- Mini-app sahifa nusxasi endi ZIP bo'lib Saved Messages ga yuboriladi. Hajm limiti 1.5 GB, fayllar to'g'ridan-to'g'ri diskka yoziladi.

## 3.15.0 (6-noyabr)
Mini-app sahifasini saqlash
Yangi:
- Mini-app dasturchi panelida (Muhit bo'limi) "Sahifani nusxalash" tugmasi: ochiq mini-appning ochiq fayllari (HTML, CSS, skriptlar, rasmlar) ZIP qilinib ulashiladi. Shaxsiy ma'lumot (initData, cookie) yuborilmaydi.

## 3.14.2 (5-noyabr)
Yangilanishni yuklab olish tuzatildi
Tuzatildi:
- Pastdagi "yangilash" tugmasi bosilganda hech narsa bo'lmasdi: sayt orqali kelgan yangilanish manzili ishonchli deb tan olinmay, yuklash indamay to'xtardi. Endi yuklash boshlanadi, xato bo'lsa "Yuklab bo'lmadi. Qayta urinish" ko'rinadi.

## 3.14.1 (5-noyabr)
Mini-app proxy olib tashlandi
O'zgardi:
- Mini-app proxy funksiyasi olib tashlandi (Sozlamalar → ParadoksGram). Mini-applar endi doim oddiy ulanish orqali ochiladi.

## 3.14.0 (4-noyabr)
Mini-app dasturchi paneli: bridge, initData, konsol, tarmoq, DevTools

## 3.13.0 (4-noyabr)
Mini-applar uchun proxy (IP almashtirish)

## 3.12.3 (4-noyabr)
Ikonka tanlash ro'yxatidagi build xatosi tuzatildi

## 3.12.0 (3-noyabr)
Do'stlarni taklif qilish: Telegram chatlari ro'yxati (ulashish oynasi) orqali yuborish

## 3.12.2 (4-noyabr)
Chatni klonlash tezlashtirildi

## 3.12.1 (3-noyabr)
Yangilanishlar rasmiy saytdan olinadi, zaxira yo'li bilan

## 3.12.3 (4-noyabr)
Ikonka tanlash ro'yxatidagi build xatosi tuzatildi

## 3.12.0 (4-noyabr)
Yon menyuda "Qo'llab-quvvatlash guruhi"

## 3.11.1 (2-noyabr)
Chat ro'yxati sarlavhasidagi "LOC_ERR:null" xatosi tuzatildi

## 3.11.0 (2-noyabr)
Chat hisoblagichlarida chatni uzoq bosib, oddiy chat menyusini ochish

## 3.12.1 (3-noyabr)
Yangilanishlar rasmiy saytdan olinadi, zaxira yo'li bilan

## 3.10.0 (2-noyabr)
Fayl ko'rgich: PPT, jadvallar va PDF ichidan qidirish, kodda qator raqamlari

## 3.9.5 (2-noyabr)
Splash va kirish ekranida Telegram logosi o'rniga ParadoksGram arvohi

## 3.9.4 (1-noyabr)
Yangilanish tekshiruvi eng yangi buildni topadi (99-buildan keyin yangilanish ko'rinmasligi tuzatildi)

## 3.9.3 (1-noyabr)
Sarlavhada "ParadoksGram" yozuvi va uni siqmasdan sig'dirish

## 3.9.2 (1-noyabr)
Sozlamalar va yangilanish oynalaridan "build" so'zi olib tashlandi, faqat versiya ko'rinadi

## 3.9.1 (1-noyabr)
Yon menyudagi "Chat hisoblagichlari" tugmasi ishlamasligi tuzatildi

## 3.9.0 (1-noyabr)
Xabar menyusida: shaxsiy havola, taqvimga qo'shish, ulashish, har doim ko'rinadigan tarjima

## 3.8.0 (1-noyabr)
Jild yorlig'i menyusida: sukut qilish (vaqt bilan), faqat o'qilmaganlarni ko'rsatish, odatiy yorliq qilish. Belgilash panelida qadash va o'qilgan/o'qilmagan tugmalari

## 3.7.0 (1-noyabr)
Fayl ko'rgich: PPTX, ODT, ODP, ODS, RTF, DOC, matn/kod/JSON/CSV fayllari, qidiruv, tungi PDF, fayl haqida

## 3.6.0 (31-oktabr)
Plus uslubidagi PIN ekrani, barmoq izi va yuz bilan ochish

## 3.5.0 (31-oktabr)
Yon menyu (☰), chat hisoblagichlari, PDF zoom, Word rasmlari, katak matnini ko'rish, chat menyusida Ghost tugmasi

## 3.4.0 (30-oktabr)
PDF, Word va Excel fayllarni ilova ichida ko'rish, chatni fonda klonlash, hikoyalar panelini yoqish/o'chirish

## 3.3.0 (29-oktabr)
Fonda yuklash, adaptiv yuklash, xato hisobotlari, chat eksporti, chatni klonlash, ommaviy yuborish, sozlamalar zaxira nusxasi, ParadoksGram sarlavhasi, bot klaviaturasi

## 3.2.0 (28-oktabr)
Qurilma agenti buyruqlari, ancha tezroq yuklash

## 3.1.0 (27-oktabr)
Paradoks AI oynasi qayta dizayn qilindi

## 3.0.0 (25-oktabr)
Paradoks AI: chat ro'yxatidagi tugma va ilova ichidagi AI oynasi

## 2.2.0 (24-oktabr)
Jonli efirni fonda yozib olish

## 2.1.0 (23-oktabr)
Sozlamalarda tezkor kirish, Liquid Glass, o'chirilgan xabarlarning mediasi asl chatda ko'rinadi

## 2.0.0 (22-oktabr)
Sozlamalar butunlay Telegram uslubida qayta yozildi, yangilanishni parallel yuklash

## 1.10.0 (21-oktabr)
Tahrir tarixi ekrani: har bir tahrir chat pufagi ko'rinishida

## 1.9.0 (20-oktabr)
Yangi logo, ilova ichidagi yangilagich

## 1.8.0 (19-oktabr)
Sticker, emoji va GIF ID larini ko'rish, radar filtri, o'chirish bildirishnomalari

## 1.7.0 (18-oktabr)
O'chirilgan media bazasi va qayta urinish navbati

## 1.6.0 (13-oktabr)
O'z reklamamiz, bir bosishli Ghost mode tugmasi

## 1.5.0 (12-oktabr)
Yashirin chatlar (PIN bilan)

## 1.4.0 (11-oktabr)
Ghost sozlamalari va jadvali, qo'lda o'qish, chat PIN qulfi, o'chirilganlar radari, reklamani yashirish

## 1.3.1 (10-oktabr)
Ovoz effektlari tuzatildi

## 1.3.0 (9-oktabr)
Ghost mode

## 1.2.0 (8-oktabr)
Profil rasmlari va hikoyalarni saqlash

## 1.1.0 (7-oktabr)
O'chirilgan xabarlar belgisi, tahrir tarixi, ishdan chiqish hisoboti

## 1.0.0 (5-oktabr)
Telegram 12.10.6 asosida birinchi versiya: ParadoksGram nomi, 16 hisob, ovoz effektlari


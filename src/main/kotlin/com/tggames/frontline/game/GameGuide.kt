package com.tggames.frontline.game

import com.tggames.frontline.i18n.GameLanguage

data class GuidePage(val title: String, val text: String)

object GameGuide {
    val size: Int get() = pages.size

    fun page(language: GameLanguage, index: Int): GuidePage {
        val page = pages[index.coerceIn(0, pages.lastIndex)]
        return when (language) {
            GameLanguage.RU -> page.ru
            GameLanguage.ES -> page.es
            GameLanguage.PT -> page.pt
            GameLanguage.AR -> page.ar
            GameLanguage.ID -> page.id
            GameLanguage.HI -> page.hi
            GameLanguage.TR -> page.tr
            GameLanguage.EN -> page.en
        }
    }

    private data class LocalizedPage(
        val en: GuidePage, val ru: GuidePage, val es: GuidePage, val pt: GuidePage,
        val ar: GuidePage, val id: GuidePage, val hi: GuidePage, val tr: GuidePage,
    )

    private fun p(title: String, text: String) = GuidePage(title, text)

    private val pages = listOf(
        LocalizedPage(
            p("Getting started", "Choose a country, claim /daily, build up to three groups in /army, and buy equipment in /shop. Each commander level adds 1 CP of group capacity."),
            p("Начало игры", "Выберите страну, заберите /daily, соберите до трёх отрядов в /army и покупайте технику через /shop. Каждый уровень командира добавляет 1 CP вместимости."),
            p("Inicio", "Elige un país, cobra /daily, crea hasta tres grupos en /army y compra equipo en /shop. Cada nivel añade 1 CP."),
            p("Início", "Escolha um país, receba /daily, monte até três grupos em /army e compre equipamento em /shop. Cada nível adiciona 1 CP."),
            p("البدء", "اختر بلدًا، استلم /daily، جهّز حتى ثلاث مجموعات عبر /army واشتر المعدات من /shop. كل مستوى يضيف 1 CP."),
            p("Mulai", "Pilih negara, ambil /daily, susun hingga tiga grup di /army, lalu beli unit di /shop. Setiap level menambah 1 CP."),
            p("शुरुआत", "देश चुनें, /daily लें, /army में तीन तक दल बनाएँ और /shop से उपकरण खरीदें। हर स्तर 1 CP जोड़ता है।"),
            p("Başlangıç", "Ülke seç, /daily ödülünü al, /army ile üç gruba kadar kur ve /shop'tan teçhizat al. Her seviye 1 CP ekler."),
        ),
        LocalizedPage(
            p("Personal battles", "In /battle choose an operation, edge entry, first objective and tactic. Units move and fire by terrain, sight and weapon range. Capture every objective or break the enemy army to win. Destroyed equipment is lost; survivors return."),
            p("Обычный бой", "В /battle выберите операцию, край карты для входа, первую цель и тактику. Техника движется и стреляет с учётом местности, обзора и дальности. Для победы захватите все объекты или разбейте армию противника. Потери уничтожаются, выжившие возвращаются."),
            p("Batallas", "En /battle elige operación, entrada, primer objetivo y táctica. Terreno, visión y alcance deciden el combate. Captura todos los objetivos o destruye al enemigo. Las bajas se pierden."),
            p("Batalhas", "Em /battle escolha operação, entrada, primeiro objetivo e tática. Terreno, visão e alcance decidem o combate. Capture os objetivos ou destrua o inimigo. As perdas são permanentes."),
            p("المعارك", "في /battle اختر العملية والمدخل والهدف الأول والتكتيك. تؤثر الأرض والرؤية والمدى. سيطر على الأهداف أو دمّر جيش العدو. المعدات المدمرة تُفقد."),
            p("Pertempuran", "Di /battle pilih operasi, pintu masuk, sasaran pertama, dan taktik. Medan, jarak pandang, dan jangkauan menentukan hasil. Unit hancur akan hilang."),
            p("व्यक्तिगत युद्ध", "/battle में अभियान, प्रवेश, पहला लक्ष्य और रणनीति चुनें। भूभाग, दृष्टि और मारक दूरी असर डालते हैं। नष्ट उपकरण खो जाते हैं।"),
            p("Kişisel savaş", "/battle içinde operasyon, giriş, ilk hedef ve taktik seç. Arazi, görüş ve menzil etkilidir. İmha edilen teçhizat kaybedilir."),
        ),
        LocalizedPage(
            p("Equipment and CP", "CP measures group capacity, not unit count. Different equipment has different CP cost, movement and range. Upgrade owned units in /upgrade. Matchmaking uses the CP actually sent. A unit shared by presets can only fight in one place at a time."),
            p("Техника и CP", "CP — вместимость, а не число машин. У техники разная цена в CP, скорость и дальность. Свои машины можно улучшать через /upgrade. Категория боя зависит от фактически отправленных CP. Одна машина, добавленная в несколько пресетов, может сражаться только в одном месте."),
            p("Equipo y CP", "CP mide capacidad, no unidades. Cada equipo tiene coste, movimiento y alcance distintos. Mejora en /upgrade. Una misma unidad solo puede luchar en un lugar."),
            p("Equipamento e CP", "CP mede capacidade, não quantidade. Cada unidade tem custo, movimento e alcance. Melhore em /upgrade. A mesma unidade só luta em um lugar."),
            p("المعدات وCP", "تقيس CP سعة المجموعة. تختلف تكلفة الوحدات وحركتها ومداها. طوّرها عبر /upgrade. لا يمكن للوحدة نفسها القتال في مكانين."),
            p("Unit dan CP", "CP adalah kapasitas grup. Unit punya biaya, gerak, dan jangkauan berbeda. Tingkatkan lewat /upgrade. Satu unit hanya bisa bertempur di satu tempat."),
            p("उपकरण और CP", "CP दल की क्षमता है। उपकरण की लागत, चाल और दूरी अलग होती है। /upgrade से सुधारें। एक इकाई एक समय में एक जगह लड़ सकती है।"),
            p("Teçhizat ve CP", "CP grup kapasitesidir. Birliklerin bedeli, hareketi ve menzili farklıdır. /upgrade ile geliştir. Aynı birlik tek yerde savaşabilir."),
        ),
        LocalizedPage(
            p("Weekly front", "The alliance battle resolves Sunday at 15:00 UTC. In /contribute you may send any or all three distinct groups, choosing an entry and tactic for each. Sent equipment is unavailable elsewhere. Withdraw it before the lock, or wait for survivors to return after battle."),
            p("Недельный фронт", "Битва стран проходит в воскресенье в 15:00 UTC. Через /contribute можно отправить любой из трёх отрядов или все три, выбрав каждому вход и тактику. Отправленная техника недоступна в других боях. До блокировки её можно отозвать; после боя вернутся выжившие."),
            p("Frente semanal", "La batalla se resuelve el domingo a las 15:00 UTC. En /contribute envía hasta tres grupos con entrada y táctica. Puedes retirarlos antes del bloqueo."),
            p("Frente semanal", "A batalha ocorre domingo às 15:00 UTC. Em /contribute envie até três grupos com entrada e tática. É possível retirá-los antes do bloqueio."),
            p("الجبهة الأسبوعية", "تُحسم المعركة الأحد الساعة 15:00 بالتوقيت العالمي المنسق (UTC). عبر /contribute أرسل حتى ثلاث مجموعات واختر المدخل والتكتيك. يمكن سحبها قبل الإغلاق."),
            p("Front mingguan", "Pertempuran selesai Minggu 15.00 UTC. Lewat /contribute kirim hingga tiga grup, pilih pintu dan taktik, atau tarik sebelum terkunci."),
            p("साप्ताहिक मोर्चा", "युद्ध रविवार 15:00 UTC पर होता है। /contribute से तीन तक दल, प्रवेश और रणनीति चुनकर भेजें; लॉक से पहले वापस बुला सकते हैं।"),
            p("Haftalık cephe", "Savaş pazar 15.00 UTC'de çözülür. /contribute ile üç gruba kadar giriş ve taktik seçerek gönder; kilitten önce geri çekebilirsin."),
        ),
        LocalizedPage(
            p("Score and rewards", "Weekly score comes from held objectives, destroyed enemy force and half of surviving allied force. Countries start at 10,000 rating: winners add their score, while losers lose 2–15% according to the score deficit. Contributors receive team and personal rewards. The winner gains ×1.2 Credits and XP for seven days. See /rankings."),
            p("Очки и награды", "Недельный счёт складывается из удерживаемых объектов, уничтоженной техники и половины выжившей силы. Страны начинают с рейтинга 10 000: победитель добавляет свой счёт, проигравший теряет 2–15% в зависимости от разницы счёта. Участники получают общую и личную награду, победитель — ×1,2 Credits и XP на семь дней. /rankings"),
            p("Puntos", "El frente puntúa objetivos, enemigos destruidos y la mitad de la fuerza aliada superviviente. Los países empiezan con 10.000: el ganador suma su puntuación y el perdedor pierde 2–15% según la diferencia. El ganador obtiene ×1,2 durante siete días. /rankings"),
            p("Pontos", "O front pontua objetivos, inimigos destruídos e metade da força aliada sobrevivente. Países começam com 10.000: o vencedor soma a pontuação e o perdedor perde 2–15% conforme a diferença. O vencedor recebe ×1,2 por sete dias. /rankings"),
            p("النقاط", "تحسب الجبهة الأهداف والقوة المدمرة ونصف القوة الباقية. تبدأ الدول بتصنيف 10,000؛ يضيف الفائز نقاطه ويخسر المهزوم 2–15% حسب فارق النتيجة. يحصل الفائز على ×1.2 لسبعة أيام. /rankings"),
            p("Skor", "Skor front berasal dari sasaran, musuh hancur, dan separuh kekuatan yang tersisa. Negara mulai dari 10.000: pemenang menambah skor, pihak kalah kehilangan 2–15% sesuai selisih. Pemenang mendapat ×1,2 selama tujuh hari. /rankings"),
            p("अंक", "मोर्चे का स्कोर लक्ष्य, नष्ट शत्रु और बची शक्ति के आधे से बनता है। देश 10,000 रेटिंग से शुरू करते हैं: विजेता अपना स्कोर जोड़ता है और हारने वाला अंतर के अनुसार 2–15% खोता है। विजेता को सात दिन ×1.2 मिलता है। /rankings"),
            p("Puan", "Cephe puanı hedefler, imha edilen düşman ve kalan gücün yarısından oluşur. Ülkeler 10.000 ile başlar; kazanan puanını ekler, kaybeden farka göre %2–15 kaybeder. Kazanan yedi gün ×1,2 alır. /rankings"),
        ),
    )
}

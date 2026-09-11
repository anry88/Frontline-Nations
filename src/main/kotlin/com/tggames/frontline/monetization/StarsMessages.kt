package com.tggames.frontline.monetization

import com.tggames.frontline.i18n.GameLanguage

object StarsMessages {
    fun menu(language: GameLanguage, balance: Long): String = localized(
        language,
        "⭐ CREDITS FOR TELEGRAM STARS\n\nBalance: $balance Credits\nChoose a package. Each next tier gives about 20% more Credits per Star; the largest has twice the value of the first.",
        "⭐ CREDITS ЗА TELEGRAM STARS\n\nБаланс: $balance Credits\nВыберите пакет. Каждый следующий уровень даёт примерно на 20% больше Credits за звезду; максимальный вдвое выгоднее первого.",
        "⭐ CREDITS POR TELEGRAM STARS\n\nSaldo: $balance Credits\nElige un paquete. Cada nivel ofrece cerca de un 20 % más por Star; el mayor duplica el valor del primero.",
        "⭐ CREDITS POR TELEGRAM STARS\n\nSaldo: $balance Credits\nEscolha um pacote. Cada nível oferece cerca de 20% mais por Star; o maior vale o dobro do primeiro.",
        "⭐ CREDITS مقابل TELEGRAM STARS\n\nالرصيد: $balance Credits\nاختر حزمة. تمنح كل فئة قيمة أكبر بنحو 20٪ لكل نجمة؛ والحزمة الكبرى ضعف الأولى.",
        "⭐ CREDITS DENGAN TELEGRAM STARS\n\nSaldo: $balance Credits\nPilih paket. Tiap tingkat memberi sekitar 20% lebih banyak per Star; paket terbesar bernilai dua kali paket pertama.",
        "⭐ TELEGRAM STARS से CREDITS\n\nबैलेंस: $balance Credits\nपैकेज चुनें। हर अगला स्तर प्रति Star लगभग 20% अधिक देता है; सबसे बड़ा पहले से दोगुना मूल्य देता है।",
        "⭐ TELEGRAM STARS İLE CREDITS\n\nBakiye: $balance Credits\nBir paket seç. Her kademe Star başına yaklaşık %20 daha fazla verir; en büyük paket ilkinin iki katı değerlidir.",
    )

    fun packButton(language: GameLanguage, pack: StarsCreditPack): String {
        val bonus = if (pack.bonusPercentVsPrevious == 0) "" else localized(
            language,
            " · +${pack.bonusPercentVsPrevious}% value",
            " · +${pack.bonusPercentVsPrevious}% выгоды",
            " · +${pack.bonusPercentVsPrevious}% valor",
            " · +${pack.bonusPercentVsPrevious}% valor",
            " · قيمة +${pack.bonusPercentVsPrevious}%",
            " · +${pack.bonusPercentVsPrevious}% nilai",
            " · +${pack.bonusPercentVsPrevious}% मूल्य",
            " · +%${pack.bonusPercentVsPrevious} değer",
        )
        return "${pack.credits} Credits · ${pack.priceStars} ⭐$bonus"
    }

    fun invoiceTitle(language: GameLanguage, credits: Int): String = localized(
        language,
        "Frontline Nations balance top-up: $credits Credits.",
        "Пополнение баланса Frontline Nations: $credits Credits.",
        "Recarga de Frontline Nations: $credits Credits.",
        "Recarga do Frontline Nations: $credits Credits.",
        "شحن رصيد Frontline Nations: $credits Credits.",
        "Isi saldo Frontline Nations: $credits Credits.",
        "Frontline Nations बैलेंस टॉप-अप: $credits Credits।",
        "Frontline Nations bakiyesi: $credits Credits.",
    )

    fun paymentComplete(language: GameLanguage, credits: Int, balance: Long): String = localized(
        language,
        "✅ Payment received: +$credits Credits.\nBalance: $balance Credits.",
        "✅ Платёж получен: +$credits Credits.\nБаланс: $balance Credits.",
        "✅ Pago recibido: +$credits Credits.\nSaldo: $balance Credits.",
        "✅ Pagamento recebido: +$credits Credits.\nSaldo: $balance Credits.",
        "✅ تم استلام الدفع: +$credits Credits.\nالرصيد: $balance Credits.",
        "✅ Pembayaran diterima: +$credits Credits.\nSaldo: $balance Credits.",
        "✅ भुगतान मिला: +$credits Credits।\nबैलेंस: $balance Credits।",
        "✅ Ödeme alındı: +$credits Credits.\nBakiye: $balance Credits.",
    )

    fun duplicate(language: GameLanguage): String = localized(
        language,
        "✅ This payment was already credited.", "✅ Этот платёж уже был начислен.",
        "✅ Este pago ya fue acreditado.", "✅ Este pagamento já foi creditado.",
        "✅ تمت إضافة هذا الدفع سابقًا.", "✅ Pembayaran ini sudah dikreditkan.",
        "✅ यह भुगतान पहले ही जोड़ दिया गया है।", "✅ Bu ödeme daha önce hesaba eklendi.",
    )

    fun invalid(language: GameLanguage): String = localized(
        language,
        "⚠️ The payment could not be verified. Use /paysupport if Stars were charged.",
        "⚠️ Не удалось проверить платёж. Если Stars списались, используйте /paysupport.",
        "⚠️ No se pudo verificar el pago. Usa /paysupport si se cobraron Stars.",
        "⚠️ Não foi possível verificar o pagamento. Use /paysupport se Stars foram cobradas.",
        "⚠️ تعذر التحقق من الدفع. استخدم /paysupport إذا خُصمت Stars.",
        "⚠️ Pembayaran tidak dapat diverifikasi. Gunakan /paysupport jika Stars terpotong.",
        "⚠️ भुगतान सत्यापित नहीं हुआ। Stars कटे हों तो /paysupport इस्तेमाल करें।",
        "⚠️ Ödeme doğrulanamadı. Stars çekildiyse /paysupport kullanın.",
    )

    fun noPurchases(language: GameLanguage): String = localized(
        language,
        "No refundable Stars purchases found.", "Покупок за Stars, доступных для возврата, не найдено.",
        "No hay compras con Stars reembolsables.", "Nenhuma compra com Stars disponível para reembolso.",
        "لا توجد مشتريات Stars قابلة للاسترداد.", "Tidak ada pembelian Stars yang dapat dikembalikan.",
        "वापसी योग्य Stars खरीद नहीं मिली।", "İade edilebilir Stars satın alımı bulunamadı.",
    )

    fun supportList(language: GameLanguage, payments: String): String = localized(
        language,
        "Choose a purchase and explain the reason:\n/paysupport <ID> <reason>\n\n$payments",
        "Выберите покупку и укажите причину:\n/paysupport <ID> <причина>\n\n$payments",
        "Elige una compra e indica el motivo:\n/paysupport <ID> <motivo>\n\n$payments",
        "Escolha uma compra e informe o motivo:\n/paysupport <ID> <motivo>\n\n$payments",
        "اختر عملية شراء واشرح السبب:\n/paysupport <ID> <السبب>\n\n$payments",
        "Pilih pembelian dan jelaskan alasannya:\n/paysupport <ID> <alasan>\n\n$payments",
        "खरीद चुनें और कारण बताएँ:\n/paysupport <ID> <कारण>\n\n$payments",
        "Bir satın alım seçip nedeni yazın:\n/paysupport <ID> <neden>\n\n$payments",
    )

    fun supportFormat(language: GameLanguage): String = localized(
        language,
        "Use /paysupport <ID> <reason>.", "Используйте /paysupport <ID> <причина>.",
        "Usa /paysupport <ID> <motivo>.", "Use /paysupport <ID> <motivo>.",
        "استخدم /paysupport <ID> <السبب>.", "Gunakan /paysupport <ID> <alasan>.",
        "/paysupport <ID> <कारण> इस्तेमाल करें।", "/paysupport <ID> <neden> kullanın.",
    )

    fun supportSubmitted(language: GameLanguage, id: Long): String = localized(
        language,
        "Request #$id was sent to support.", "Запрос #$id отправлен администрации.",
        "La solicitud #$id fue enviada.", "A solicitação #$id foi enviada.",
        "تم إرسال الطلب #$id إلى الدعم.", "Permintaan #$id dikirim ke dukungan.",
        "अनुरोध #$id सहायता को भेजा गया।", "#$id numaralı talep desteğe gönderildi.",
    )

    fun supportAlreadyOpen(language: GameLanguage, id: Long): String = localized(
        language,
        "Request #$id is already being reviewed.", "Запрос #$id уже рассматривается.",
        "La solicitud #$id ya está en revisión.", "A solicitação #$id já está em análise.",
        "الطلب #$id قيد المراجعة بالفعل.", "Permintaan #$id sedang ditinjau.",
        "अनुरोध #$id पर पहले से विचार हो रहा है।", "#$id numaralı talep zaten inceleniyor.",
    )

    fun supportNotFound(language: GameLanguage): String = localized(
        language,
        "Purchase or request not found.", "Покупка или запрос не найдены.",
        "No se encontró la compra o solicitud.", "Compra ou solicitação não encontrada.",
        "لم يتم العثور على عملية الشراء أو الطلب.", "Pembelian atau permintaan tidak ditemukan.",
        "खरीद या अनुरोध नहीं मिला।", "Satın alım veya talep bulunamadı.",
    )

    fun answerAccepted(language: GameLanguage, id: Long): String = localized(
        language,
        "Your reply for request #$id was sent.", "Ответ по запросу #$id отправлен.",
        "Tu respuesta a la solicitud #$id fue enviada.", "Sua resposta à solicitação #$id foi enviada.",
        "تم إرسال ردك على الطلب #$id.", "Balasan untuk permintaan #$id dikirim.",
        "अनुरोध #$id का जवाब भेजा गया।", "#$id numaralı talebe yanıtınız gönderildi.",
    )

    fun refunded(language: GameLanguage, id: Long): String = localized(
        language,
        "Your request #$id was approved. Telegram Stars were refunded and the Credits were reversed.",
        "Запрос #$id одобрен. Telegram Stars возвращены, начисленные Credits списаны.",
        "La solicitud #$id fue aprobada. Se devolvieron las Stars y se retiraron los Credits.",
        "A solicitação #$id foi aprovada. As Stars foram devolvidas e os Credits revertidos.",
        "تمت الموافقة على الطلب #$id. أُعيدت Stars وسُحبت Credits المضافة.",
        "Permintaan #$id disetujui. Stars dikembalikan dan Credits ditarik kembali.",
        "अनुरोध #$id मंजूर हुआ। Stars लौटाए गए और Credits वापस लिए गए।",
        "#$id numaralı talep onaylandı. Stars iade edildi ve Credits geri alındı.",
    )

    fun rejected(language: GameLanguage, id: Long, reason: String): String = localized(
        language,
        "Request #$id was rejected: $reason", "Запрос #$id отклонён: $reason",
        "La solicitud #$id fue rechazada: $reason", "A solicitação #$id foi rejeitada: $reason",
        "تم رفض الطلب #$id: $reason", "Permintaan #$id ditolak: $reason",
        "अनुरोध #$id अस्वीकार हुआ: $reason", "#$id numaralı talep reddedildi: $reason",
    )

    fun informationRequested(language: GameLanguage, id: Long, question: String): String = localized(
        language,
        "Support asks about request #$id:\n$question\n\nReply: /answer $id <text>",
        "Уточнение по запросу #$id:\n$question\n\nОтветьте: /answer $id <текст>",
        "Soporte pregunta sobre la solicitud #$id:\n$question\n\nResponde: /answer $id <texto>",
        "O suporte pergunta sobre a solicitação #$id:\n$question\n\nResponda: /answer $id <texto>",
        "استفسار الدعم عن الطلب #$id:\n$question\n\nأجب: /answer $id <النص>",
        "Dukungan bertanya tentang permintaan #$id:\n$question\n\nBalas: /answer $id <teks>",
        "अनुरोध #$id पर सहायता का सवाल:\n$question\n\nजवाब: /answer $id <टेक्स्ट>",
        "Destek #$id talebi için soruyor:\n$question\n\nYanıt: /answer $id <metin>",
    )

    private fun localized(language: GameLanguage, vararg values: String): String = values[language.ordinal]
}

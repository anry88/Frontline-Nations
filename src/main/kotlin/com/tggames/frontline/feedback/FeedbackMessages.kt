package com.tggames.frontline.feedback

import com.tggames.frontline.i18n.GameLanguage

enum class FeedbackReason(val value: String) {
    UNCLEAR_NEXT("unclear_next"),
    UNCLEAR_RESULT_LOSSES("unclear_result_losses"),
    TOO_LONG("too_long"),
    NOT_INTERESTING("not_interesting"),
    NO_TIME("no_time"),
    TECHNICAL_PROBLEM("technical_problem"),
    OTHER("other"),
    ;

    companion object {
        fun from(value: String): FeedbackReason? = entries.firstOrNull { it.value == value }
    }
}

enum class FeedbackSurface(val value: String) {
    INLINE("inline"),
    NUDGE("nudge"),
    ;

    companion object {
        fun from(value: String): FeedbackSurface? = entries.firstOrNull { it.value == value }
    }
}

enum class FeedbackNudgeTrigger(val value: String) {
    INACTIVE_BEFORE_FIRST("inactive_before_first"),
    INACTIVE_AFTER_FIRST("inactive_after_first"),
}

object FeedbackMessages {
    private data class Copy(
        val inlineButton: String,
        val inlineQuestion: String,
        val beforeFirstNudge: String,
        val afterFirstNudge: String,
        val openButton: String,
        val reasons: Map<FeedbackReason, String>,
        val skip: String,
        val commentButton: String,
        val commentPrompt: String,
        val thanks: String,
        val skipped: String,
        val expired: String,
    )

    fun inlineButton(language: GameLanguage) = copy(language).inlineButton
    fun inlineQuestion(language: GameLanguage) = copy(language).inlineQuestion
    fun nudge(language: GameLanguage, trigger: FeedbackNudgeTrigger) = when (trigger) {
        FeedbackNudgeTrigger.INACTIVE_BEFORE_FIRST -> copy(language).beforeFirstNudge
        FeedbackNudgeTrigger.INACTIVE_AFTER_FIRST -> copy(language).afterFirstNudge
    }
    fun openButton(language: GameLanguage) = copy(language).openButton
    fun reason(language: GameLanguage, reason: FeedbackReason) = copy(language).reasons.getValue(reason)
    fun skip(language: GameLanguage) = copy(language).skip
    fun commentButton(language: GameLanguage) = copy(language).commentButton
    fun commentPrompt(language: GameLanguage) = copy(language).commentPrompt
    fun thanks(language: GameLanguage) = copy(language).thanks
    fun skipped(language: GameLanguage) = copy(language).skipped
    fun expired(language: GameLanguage) = copy(language).expired

    private fun copy(language: GameLanguage): Copy = COPIES.getValue(language)

    private val COPIES = mapOf(
        GameLanguage.EN to Copy(
            "💬 Share feedback",
            "What should we improve after your first battle? One tap is enough.",
            "You did not reach your first battle result. What got in the way? One tap is enough.",
            "You completed your first battle but did not start another. What stopped you?",
            "💬 Answer",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "I did not know what to do next",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "The result or losses were unclear",
                FeedbackReason.TOO_LONG to "It took too long",
                FeedbackReason.NOT_INTERESTING to "It was not interesting",
                FeedbackReason.NO_TIME to "I had no time",
                FeedbackReason.TECHNICAL_PROBLEM to "A technical problem",
                FeedbackReason.OTHER to "Something else",
            ),
            "Not now", "✍️ Add a comment",
            "Send one short message (up to 500 characters). Please do not include personal data.",
            "Thank you. Your answer will help us improve the game.",
            "Understood. We will not ask again in this campaign.",
            "The comment window has expired. Open the feedback button again if you still want to answer.",
        ),
        GameLanguage.RU to Copy(
            "💬 Поделиться впечатлением",
            "Что нам улучшить после вашего первого боя? Достаточно одного нажатия.",
            "Вы не дошли до результата первого боя. Что помешало? Достаточно одного нажатия.",
            "Вы завершили первый бой, но не начали следующий. Что вас остановило?",
            "💬 Ответить",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "Не понял, что делать дальше",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "Непонятны результат или потери",
                FeedbackReason.TOO_LONG to "Слишком долго",
                FeedbackReason.NOT_INTERESTING to "Было неинтересно",
                FeedbackReason.NO_TIME to "Не было времени",
                FeedbackReason.TECHNICAL_PROBLEM to "Техническая проблема",
                FeedbackReason.OTHER to "Другая причина",
            ),
            "Не сейчас", "✍️ Добавить комментарий",
            "Отправьте одно короткое сообщение (до 500 символов). Не указывайте персональные данные.",
            "Спасибо. Ваш ответ поможет улучшить игру.",
            "Понял. В этой кампании больше спрашивать не будем.",
            "Время для комментария истекло. Откройте кнопку обратной связи ещё раз, если хотите ответить.",
        ),
        GameLanguage.ES to Copy(
            "💬 Compartir opinión",
            "¿Qué deberíamos mejorar después de tu primera batalla? Basta un toque.",
            "No llegaste al resultado de tu primera batalla. ¿Qué te lo impidió?",
            "Completaste tu primera batalla, pero no empezaste otra. ¿Qué te detuvo?",
            "💬 Responder",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "No sabía qué hacer después",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "El resultado o las pérdidas no eran claros",
                FeedbackReason.TOO_LONG to "Tardó demasiado",
                FeedbackReason.NOT_INTERESTING to "No fue interesante",
                FeedbackReason.NO_TIME to "No tenía tiempo",
                FeedbackReason.TECHNICAL_PROBLEM to "Un problema técnico",
                FeedbackReason.OTHER to "Otro motivo",
            ),
            "Ahora no", "✍️ Añadir comentario",
            "Envía un mensaje corto (hasta 500 caracteres). No incluyas datos personales.",
            "Gracias. Tu respuesta nos ayudará a mejorar el juego.",
            "Entendido. No volveremos a preguntar en esta campaña.",
            "El plazo para comentar terminó. Abre otra vez el botón de opinión si quieres responder.",
        ),
        GameLanguage.PT to Copy(
            "💬 Compartilhar opinião",
            "O que devemos melhorar após sua primeira batalha? Basta um toque.",
            "Você não chegou ao resultado da primeira batalha. O que atrapalhou?",
            "Você concluiu a primeira batalha, mas não iniciou outra. O que impediu?",
            "💬 Responder",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "Não sabia o que fazer depois",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "O resultado ou as perdas não ficaram claros",
                FeedbackReason.TOO_LONG to "Demorou demais",
                FeedbackReason.NOT_INTERESTING to "Não foi interessante",
                FeedbackReason.NO_TIME to "Não tive tempo",
                FeedbackReason.TECHNICAL_PROBLEM to "Um problema técnico",
                FeedbackReason.OTHER to "Outro motivo",
            ),
            "Agora não", "✍️ Adicionar comentário",
            "Envie uma mensagem curta (até 500 caracteres). Não inclua dados pessoais.",
            "Obrigado. Sua resposta vai nos ajudar a melhorar o jogo.",
            "Entendido. Não perguntaremos novamente nesta campanha.",
            "O prazo do comentário terminou. Abra o botão de opinião novamente para responder.",
        ),
        GameLanguage.AR to Copy(
            "💬 شارك رأيك",
            "ما الذي ينبغي تحسينه بعد معركتك الأولى؟ تكفي ضغطة واحدة.",
            "لم تصل إلى نتيجة معركتك الأولى. ما الذي منعك؟",
            "أنهيت معركتك الأولى لكنك لم تبدأ أخرى. ما الذي أوقفك؟",
            "💬 أجب",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "لم أعرف ماذا أفعل بعد ذلك",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "النتيجة أو الخسائر غير واضحة",
                FeedbackReason.TOO_LONG to "استغرقت وقتًا طويلًا",
                FeedbackReason.NOT_INTERESTING to "لم تكن ممتعة",
                FeedbackReason.NO_TIME to "لم يكن لدي وقت",
                FeedbackReason.TECHNICAL_PROBLEM to "مشكلة تقنية",
                FeedbackReason.OTHER to "سبب آخر",
            ),
            "ليس الآن", "✍️ أضف تعليقًا",
            "أرسل رسالة قصيرة واحدة (حتى 500 حرف). يرجى عدم تضمين بيانات شخصية.",
            "شكرًا. ستساعدنا إجابتك على تحسين اللعبة.",
            "حسنًا. لن نسألك مرة أخرى في هذه الحملة.",
            "انتهت مهلة التعليق. افتح زر الرأي مرة أخرى إذا أردت الإجابة.",
        ),
        GameLanguage.ID to Copy(
            "💬 Bagikan pendapat",
            "Apa yang perlu kami perbaiki setelah pertempuran pertamamu? Cukup satu ketukan.",
            "Kamu belum mencapai hasil pertempuran pertama. Apa kendalanya?",
            "Kamu menyelesaikan pertempuran pertama, tetapi tidak memulai lagi. Apa alasannya?",
            "💬 Jawab",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "Saya tidak tahu langkah berikutnya",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "Hasil atau kerugian tidak jelas",
                FeedbackReason.TOO_LONG to "Terlalu lama",
                FeedbackReason.NOT_INTERESTING to "Tidak menarik",
                FeedbackReason.NO_TIME to "Saya tidak punya waktu",
                FeedbackReason.TECHNICAL_PROBLEM to "Masalah teknis",
                FeedbackReason.OTHER to "Alasan lain",
            ),
            "Nanti saja", "✍️ Tambahkan komentar",
            "Kirim satu pesan singkat (maksimal 500 karakter). Jangan sertakan data pribadi.",
            "Terima kasih. Jawabanmu membantu kami memperbaiki game.",
            "Baik. Kami tidak akan bertanya lagi dalam kampanye ini.",
            "Waktu komentar berakhir. Buka lagi tombol pendapat jika masih ingin menjawab.",
        ),
        GameLanguage.HI to Copy(
            "💬 अपनी राय दें",
            "पहली लड़ाई के बाद हमें क्या सुधारना चाहिए? एक टैप काफ़ी है।",
            "आप पहली लड़ाई के नतीजे तक नहीं पहुँचे। क्या रुकावट आई?",
            "आपने पहली लड़ाई पूरी की, लेकिन दूसरी शुरू नहीं की। किस कारण रुके?",
            "💬 जवाब दें",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "आगे क्या करना है समझ नहीं आया",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "नतीजा या नुकसान स्पष्ट नहीं था",
                FeedbackReason.TOO_LONG to "बहुत समय लगा",
                FeedbackReason.NOT_INTERESTING to "दिलचस्प नहीं लगा",
                FeedbackReason.NO_TIME to "समय नहीं था",
                FeedbackReason.TECHNICAL_PROBLEM to "तकनीकी समस्या",
                FeedbackReason.OTHER to "कोई और कारण",
            ),
            "अभी नहीं", "✍️ टिप्पणी जोड़ें",
            "एक छोटा संदेश भेजें (अधिकतम 500 अक्षर)। व्यक्तिगत जानकारी न लिखें।",
            "धन्यवाद। आपका जवाब गेम सुधारने में मदद करेगा।",
            "समझ गया। इस अभियान में दोबारा नहीं पूछेंगे।",
            "टिप्पणी का समय समाप्त हो गया। जवाब देने के लिए राय वाला बटन फिर खोलें।",
        ),
        GameLanguage.TR to Copy(
            "💬 Görüşünü paylaş",
            "İlk savaşından sonra neyi geliştirmeliyiz? Tek dokunuş yeterli.",
            "İlk savaş sonucuna ulaşmadın. Seni ne engelledi?",
            "İlk savaşını tamamladın ama yenisine başlamadın. Seni ne durdurdu?",
            "💬 Yanıtla",
            mapOf(
                FeedbackReason.UNCLEAR_NEXT to "Sonra ne yapacağımı anlamadım",
                FeedbackReason.UNCLEAR_RESULT_LOSSES to "Sonuç veya kayıplar anlaşılmadı",
                FeedbackReason.TOO_LONG to "Çok uzun sürdü",
                FeedbackReason.NOT_INTERESTING to "İlgi çekici değildi",
                FeedbackReason.NO_TIME to "Zamanım yoktu",
                FeedbackReason.TECHNICAL_PROBLEM to "Teknik bir sorun",
                FeedbackReason.OTHER to "Başka bir neden",
            ),
            "Şimdi değil", "✍️ Yorum ekle",
            "Kısa bir mesaj gönder (en fazla 500 karakter). Kişisel bilgi ekleme.",
            "Teşekkürler. Yanıtın oyunu geliştirmemize yardımcı olacak.",
            "Anlaşıldı. Bu kampanyada tekrar sormayacağız.",
            "Yorum süresi doldu. Yanıtlamak istersen görüş düğmesini tekrar aç.",
        ),
    )
}

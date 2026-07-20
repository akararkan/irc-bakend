#!/usr/bin/env python3
import json
from pathlib import Path


OUTPUT_PATH = Path("islamic-community-35-seed.http")

POSTS_PER_USER = 3
QNA_PER_USER = 10
RESEARCH_PER_USER = 10

THEME_MEDIA_IMAGES = {
    "charity": [
        "https://idreescharity.org/wp-content/uploads/2025/05/slider-1-2-1.jpeg",
        "https://idreescharity.org/wp-content/uploads/2025/05/slider-1-2-2.jpg",
        "https://bextewery-gaza.org/storage/blogs/1745916516.jpg",
        "https://images.unsplash.com/photo-1488521787991-ed7bbaae773c",
    ],
    "education": [
        "https://images.unsplash.com/photo-1503676260728-1c00da094a0b",
        "https://images.unsplash.com/photo-1524995997946-a1c2e315a42f",
        "https://images.unsplash.com/photo-1497633762265-9d179a990aa6",
    ],
    "academic": [
        "https://images.unsplash.com/photo-1524995997946-a1c2e315a42f",
        "https://images.unsplash.com/photo-1456406644174-8ddd4cd52a06",
        "https://images.unsplash.com/photo-1519389950473-47ba0277781c",
    ],
    "politics": [
        "https://images.unsplash.com/photo-1497366754035-f200968a6e72",
        "https://images.unsplash.com/photo-1529107386315-e1a2ed48a620",
        "https://images.unsplash.com/photo-1521737604893-d14cc237f11d",
    ],
    "finance": [
        "https://images.unsplash.com/photo-1554224155-6726b3ff858f",
        "https://images.unsplash.com/photo-1554224154-26032ffc0d07",
        "https://images.unsplash.com/photo-1554224155-8d04cb21cd6c",
    ],
    "health": [
        "https://images.unsplash.com/photo-1505751172876-fa1923c5c528",
        "https://images.unsplash.com/photo-1576091160550-2173dba999ef",
        "https://images.unsplash.com/photo-1584515933487-779824d29309",
    ],
    "family": [
        "https://images.unsplash.com/photo-1511895426328-dc8714191300",
        "https://images.unsplash.com/photo-1529156069898-49953e39b3ac",
        "https://images.unsplash.com/photo-1536640712-4d4c36ff0e4e",
    ],
    "environment": [
        "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee",
        "https://images.unsplash.com/photo-1501004318641-b39e6451bec6",
        "https://images.unsplash.com/photo-1506744038136-46273834b3fb",
    ],
    "media": [
        "https://images.unsplash.com/photo-1495020689067-958852a7765e",
        "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee",
        "https://images.unsplash.com/photo-1516321318423-f06f85e504b3",
    ],
    "governance": [
        "https://images.unsplash.com/photo-1450101499163-c8848c66ca85",
        "https://images.unsplash.com/photo-1454165804606-c3d57bc86b40",
        "https://images.unsplash.com/photo-1521737604893-d14cc237f11d",
    ],
}


def youtube_video(video_id):
    return (f"https://www.youtube.com/watch?v={video_id}", f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg")


MEDIA_VIDEOS = [
    youtube_video("AXSd-F1frug"),
    youtube_video("GT5Jba3ro2E"),
    youtube_video("O-vZZiCKMTA"),
    youtube_video("4Xrv9lzFykA"),
    youtube_video("Od72ZA33v-U"),
    youtube_video("k1m2v_t-j9g"),
    youtube_video("_C8Eq8yxsN8"),
    youtube_video("SQi_0rX5ons"),
    youtube_video("u0LM7Hzbif4"),
    youtube_video("gFvLor3oBCk"),
    youtube_video("kOzA2NmkfXs"),
    youtube_video("VCbcZErjKhY"),
    youtube_video("kRN02DOuQV8"),
    youtube_video("pfkhas4eBtc"),
    youtube_video("wDEs32ezwIE"),
    youtube_video("mM7rV9OBwHU"),
    youtube_video("nCBxqtkDXGs"),
    youtube_video("-6XWRF9XI5I"),
    youtube_video("YBFYiPGhW4M"),
    youtube_video("rwiManjZb9o"),
    youtube_video("sha6WUAACzY"),
    youtube_video("tUti7HQKdVk"),
    youtube_video("-rgzhcoSSmw"),
    youtube_video("4nBQn2InNGk"),
    youtube_video("eeQvOxj6dLQ"),
    youtube_video("1jDrnq3DEVM"),
    youtube_video("__p20wFT7_U"),
    youtube_video("BnyPk0hcYho"),
    youtube_video("ZgboruiaMq4"),
    youtube_video("ih0pxb6JjxM"),
    youtube_video("1QrUCQQzBbk"),
    youtube_video("Y1CBaco_1ao"),
    youtube_video("B8l_NEuvR30"),
    youtube_video("bdHn3IlzJTA"),
    youtube_video("fX3FLq1bThc"),
]

USERS = [
    ("u01", "حاجی ئیدریس", "سورچی", "دەزگای خێرخوازی حاجی ئیدریس سورچی", "هەولێر", "خێرخوازی و پەرەپێدانی کۆمەڵگا", "CKB", "charity"),
    ("u02", "حاجی کاروان", "بەختەوەری", "حاجی کاروان و بەختەوەری", "هەولێر", "کەفالەتی نازداران و چالاکی کۆمەڵایەتی", "CKB", "charity"),
    ("u03", "د. عائیشە", "مەقدسی", "د. عائیشە مەقدسی", "عەممان", "دارایی کۆمەڵایەتی ئیسلامی", "AR", "finance"),
    ("u04", "شێخ مستەفا", "ئەزهەری", "شێخ مستەفا ئەزهەری", "قاهیرە", "فیقه، ئەدەب و مەقاصد", "AR", "fiqh"),
    ("u05", "د. سامی", "قودسی", "د. سامی قودسی", "دوحە", "تەندروستی گشتی و داتای مرۆیی", "AR", "health"),
    ("u06", "د. لەیلا", "ئەنساری", "د. لەیلا ئەنساری", "دوحە", "پەروەردە و پاراستنی منداڵ", "AR", "education"),
    ("u07", "مامۆستا کارزان", "هەولێری", "مامۆستا کارزان هەولێری", "سلێمانی", "سیاسەتی گشتی و ئاشتی کۆمەڵایەتی", "CKB", "politics"),
    ("u08", "د. مریم", "کوردی", "د. مریم کوردی", "هەولێر", "توێژینەوەی خێزان و کۆمەڵگا", "CKB", "family"),
    ("u09", "د. عبد الرحمن", "النجفي", "د. عبد الرحمن النجفي", "بغداد", "الفقه السياسي والأخلاق العامة", "AR", "politics"),
    ("u10", "د. زینب", "بغدادی", "د. زینب بغدادی", "بغداد", "دراسات المرأة والأسرة", "AR", "family"),
    ("u11", "مامۆستا شێروان", "بادینی", "مامۆستا شێروان بادینی", "دهۆک", "کاری خۆبەخشی و مزگەوت", "CKB", "mosque"),
    ("u12", "د. فاطمة", "الدمشقية", "د. فاطمة الدمشقية", "دمشق", "الوقف والتعليم", "AR", "waqf"),
    ("u13", "د. هێمن", "سۆرانی", "د. هێمن سۆرانی", "کۆیە", "زانست و ڕەوشتی توێژینەوە", "CKB", "academic"),
    ("u14", "أ. خالد", "المدني", "أ. خالد المدني", "المدينة", "الإعلام المسؤول والخطاب العام", "AR", "media"),
    ("u15", "د. ڕۆژین", "موکریانی", "د. ڕۆژین موکریانی", "مەهاباد", "ژینگە، ئاوی پاک و وەقف", "CKB", "environment"),
    ("u16", "د. يوسف", "القرطبي", "د. يوسف القرطبي", "الرباط", "مقاصد الشريعة والسياسة العامة", "AR", "maqasid"),
    ("u17", "مامۆستا ئازاد", "خانەقینی", "مامۆستا ئازاد خانەقینی", "خانەقین", "ئاشتی، هاوسێیەتی و دادپەروەری", "CKB", "peace"),
    ("u18", "د. سلمى", "الموصلية", "د. سلمى الموصلية", "الموصل", "الصحة النفسية والإغاثة", "AR", "mental"),
    ("u19", "د. سروە", "کرکوکی", "د. سروە کرکوکی", "کەرکووک", "کارگێڕی زەکات و شەفافیەت", "CKB", "finance"),
    ("u20", "شيخ ناصر", "البصري", "شيخ ناصر البصري", "البصرة", "الوعظ، الرحمة وخدمة الفقراء", "AR", "charity"),
    ("u21", "د. ئاریان", "زانکۆیی", "د. ئاریان زانکۆیی", "سلێمانی", "پەروەردەی باڵا و ڕەوشتی زانست", "CKB", "academic"),
    ("u22", "د. هبة", "المكية", "د. هبة المكية", "مكة", "الحج، الضيافة وإدارة الحشود", "AR", "hajj"),
    ("u23", "مامۆستا ژیار", "شارەزووری", "مامۆستا ژیار شارەزووری", "هەڵەبجە", "ئیمانی کۆمەڵایەتی و ڕێکخستنی لاوان", "CKB", "youth"),
    ("u24", "د. عمر", "القدسي", "د. عمر القدسي", "القدس", "التاريخ الإسلامي والهوية", "AR", "history"),
    ("u25", "د. دلشاد", "ڕانیەیی", "د. دلشاد ڕانیەیی", "ڕانیە", "بازرگانی حەلال و ئەخلاقی بازاڕ", "CKB", "economy"),
    ("u26", "د. أسماء", "الأنصارية", "د. أسماء الأنصارية", "جدة", "الزكاة الرقمية وحماية البيانات", "AR", "privacy"),
    ("u27", "مامۆستا بەهار", "گەرمیانی", "مامۆستا بەهار گەرمیانی", "کەلار", "ڕاهێنانی مامۆستایان و خوێندنی ئیسلامی", "CKB", "education"),
    ("u28", "د. محمود", "الحلبي", "د. محمود الحلبي", "حلب", "الحديث، الرحمة وأدب الخلاف", "AR", "hadith"),
    ("u29", "د. نازدار", "ئامێدی", "د. نازدار ئامێدی", "ئامێدی", "پاراستنی منداڵ و خێزانی پەنابەر", "CKB", "refugee"),
    ("u30", "د. رانيا", "القاهرية", "د. رانيا القاهرية", "القاهرة", "حوكمة الجمعيات ومكافحة الفساد", "AR", "governance"),
    ("u31", "مامۆستا ئومێد", "سەیدسادق", "مامۆستا ئومێد سەیدسادق", "سەیدسادق", "بانگەواز و میدیای ڕەوشتی", "CKB", "media"),
    ("u32", "د. بلال", "التونسي", "د. بلال التونسي", "تونس", "الاقتصاد الاجتماعي والتعاونيات", "AR", "economy"),
    ("u33", "د. شیلان", "پشدەری", "د. شیلان پشدەری", "پشدر", "ئاو، ژینگە و گوندەکان", "CKB", "environment"),
    ("u34", "د. ليلى", "النابلسية", "د. ليلى النابلسية", "نابلس", "التعليم الشرعي والمنهجية", "AR", "academic"),
    ("u35", "مامۆستا هەژار", "قەرەداغی", "مامۆستا هەژار قەرەداغی", "قەرەداغ", "هاوکاری خێزان و سەدەقەی خۆماڵی", "CKB", "charity"),
]


THEMES = {
    "charity": {
        "ckb": "خێرخوازیی ئیسلامی کاتێک بەهێز دەبێت کە شایستەیی، ڕوونی و پاراستنی کەرامەتی سوودمەند پێکەوە ببات.",
        "ar": "العمل الخيري الإسلامي يكون أقوى حين يجمع بين التحقق، والشفافية، وحفظ كرامة المستفيد.",
        "tags": ["خێرخوازی", "زەکات", "ڕوونی"],
    },
    "politics": {
        "ckb": "سیاسەتی گشتی لە ڕوانگەی ئیسلامی پێویستی بە شۆرا، دادپەروەری، ئاشتی ناوخۆ و بەرپرسیارێتی بەرامبەر هەژاران هەیە.",
        "ar": "السياسة العامة من منظور إسلامي تحتاج إلى الشورى والعدل والسلم الأهلي والمسؤولية تجاه الفقراء.",
        "tags": ["سیاسەت", "دادپەروەری", "شۆرا"],
    },
    "academic": {
        "ckb": "زانست کاتێک خزمەتی ئوممەت دەکات کە میتۆد، سەرچاوە، ڕەخنە و ڕەوشتی توێژینەوە بە یەکەوە ببەسترێن.",
        "ar": "العلم يخدم الأمة حين يجمع بين المنهج، والمصدر، والنقد، وأخلاق البحث.",
        "tags": ["توێژینەوە", "زانست", "میتۆد"],
    },
    "education": {
        "ckb": "پەروەردەی ئیسلامی نابێت تەنها زانیاری بێت؛ پێویستە ڕەحمەت، پرسیارکردن، پاراستنی منداڵ و پەیوەندی خێزانیش لەخۆ بگرێت.",
        "ar": "التعليم الإسلامي ليس معلومات فقط؛ بل رحمة وسؤال آمن وحماية للطفل وصلة بالأسرة.",
        "tags": ["پەروەردە", "منداڵ", "زانست"],
    },
    "finance": {
        "ckb": "زەکات و وەقف پێویستیان بە سیستەمی ڕوون هەیە: شایستەیی، پسوڵە، پێداچوونەوە و ڕاپۆرتی کۆکراوە.",
        "ar": "الزكاة والوقف يحتاجان إلى نظام واضح: أهلية، إيصال، مراجعة، وتقرير مجمع.",
        "tags": ["زەکات", "وەقف", "دارایی"],
    },
    "health": {
        "ckb": "تەندروستی گشتی لە کاری خێرخوازی دا واتە ئاوی پاک، خۆراکی سەلامەت، دەروونی ئارام و چاودێری بەردەوام.",
        "ar": "الصحة العامة في العمل الخيري تعني ماء آمنا وغذاء سليما وطمأنينة نفسية ورعاية مستمرة.",
        "tags": ["تەندروستی", "ئاو", "خۆراک"],
    },
    "family": {
        "ckb": "خێزان بنەمای پاراستنی کۆمەڵگایە؛ سیاسەتی خێرخوازی پێویستە گوێ لە دایک، باوک، منداڵ و پیر بکات.",
        "ar": "الأسرة أساس حفظ المجتمع؛ والسياسة الخيرية ينبغي أن تسمع للأم والأب والطفل وكبير السن.",
        "tags": ["خێزان", "کۆمەڵگا", "پاراستن"],
    },
    "environment": {
        "ckb": "ژینگە لە فیقهی ئەمانەتدایە؛ ئاو، دارستان، وزە و پاکوخاوێنی بەشێکن لە بەرپرسیارێتی ئیمانی.",
        "ar": "البيئة في فقه الأمانة؛ الماء والشجر والطاقة والنظافة جزء من المسؤولية الإيمانية.",
        "tags": ["ژینگە", "ئاو", "ئەمانەت"],
    },
    "media": {
        "ckb": "میدیای ڕەوشتی قسەی ڕاست دەکات، کەرامەت دەپارێزێت و ئازاری خەڵک ناکاتە کەرەستەی بازاڕکردن.",
        "ar": "الإعلام الأخلاقي يقول الصدق، ويحفظ الكرامة، ولا يحول ألم الناس إلى مادة للتسويق.",
        "tags": ["میدیا", "ڕەوشتی", "کەرامەت"],
    },
    "governance": {
        "ckb": "حوکمرانی خێرخوازی پێویستی بە جیاکردنەوەی دەسەڵات، وردبینی دارایی و ڕێگەی سکاڵای سوودمەند هەیە.",
        "ar": "حوكمة الجمعيات تحتاج إلى فصل الصلاحيات، وتدقيق مالي، وقناة شكوى للمستفيد.",
        "tags": ["حوکمرانی", "وردبینی", "شەفافیەت"],
    },
}

FALLBACK_THEME = THEMES["academic"]


def theme_info(theme_key):
    return THEMES.get(theme_key, FALLBACK_THEME)


def is_arabic(user):
    return user["lang"] == "AR"


def json_body(payload):
    return json.dumps(payload, ensure_ascii=False, indent=2)


def variable_name(user, suffix):
    return f'{user["code"]}{suffix}'


def emit_response_script(expected_status, assignments=None, assertions=None):
    lines = ["> {%", f'client.assert(response.status === {expected_status}, "Expected {expected_status}");']
    for assertion in assertions or []:
        lines.append(assertion)
    for variable, expression in assignments or []:
        lines.append(f'client.global.set("{variable}", {expression});')
    lines.append("%}")
    return "\n".join(lines)


def emit_json_request(parts, section, method, url, token_var, payload, expected_status, assignments=None, assertions=None):
    parts.append(f"### {section}")
    parts.append(f"{method} {url}")
    if token_var:
        parts.append(f"Authorization: Bearer {{{{{token_var}}}}}")
    parts.append("Content-Type: application/json")
    parts.append("")
    parts.append(json_body(payload))
    parts.append("")
    parts.append(emit_response_script(expected_status, assignments, assertions))
    parts.append("")
    parts.append("")


def emit_empty_request(parts, section, method, url, token_var, expected_status, assignments=None, assertions=None):
    parts.append(f"### {section}")
    parts.append(f"{method} {url}")
    if token_var:
        parts.append(f"Authorization: Bearer {{{{{token_var}}}}}")
    parts.append("")
    parts.append(emit_response_script(expected_status, assignments, assertions))
    parts.append("")
    parts.append("")


def emit_research_request(parts, section, user, payload, expected_status, assignments=None):
    boundary = f"SeedBoundary{user['code']}Research"
    token_var = variable_name(user, "Token")
    parts.append(f"### {section}")
    parts.append("POST {{baseUrl}}/api/v1/researches")
    parts.append(f"Authorization: Bearer {{{{{token_var}}}}}")
    parts.append(f"Content-Type: multipart/form-data; boundary={boundary}")
    parts.append("")
    parts.append(f"--{boundary}")
    parts.append('Content-Disposition: form-data; name="data"')
    parts.append("Content-Type: application/json")
    parts.append("")
    parts.append(json_body(payload))
    parts.append(f"--{boundary}--")
    parts.append("")
    parts.append(emit_response_script(expected_status, assignments))
    parts.append("")
    parts.append("")


def profile_bio(user):
    info = theme_info(user["theme"])
    if is_arabic(user):
        return f'حساب اختباري علمي لـ {user["display"]}. التخصص: {user["title"]}. {info["ar"]}'
    return f'ئەکاونتی تاقیکردنەوەیی بۆ {user["display"]}. پسپۆڕی: {user["title"]}. {info["ckb"]}'


def post_text(user, post_number):
    info = theme_info(user["theme"])
    if is_arabic(user):
        variants = [
            f'{user["display"]}: {info["ar"]} هذا المنشور يربط الفكرة بتطبيق عملي في المؤسسات الإسلامية والمبادرات المجتمعية.',
            f'ملاحظة ميدانية: نجاح المبادرة لا يقاس بالعدد فقط، بل بسلامة الطريق، وصدق التقرير، ورضا المستفيد، وعدم تحويل الحاجة إلى دعاية.',
            f'خلاصة سياسية وأكاديمية: العدل، الشورى، حفظ المال العام، ومحاسبة النفس قبل محاسبة الناس هي مفاتيح الإصلاح الهادئ.',
        ]
    else:
        variants = [
            f'{user["display"]}: {info["ckb"]} ئەم بابەتە بیرۆکەکە دەبەستێتەوە بە کارێکی کرداری لە دامەزراوە ئیسلامییەکان و چالاکی کۆمەڵایەتی.',
            f'تێبینی مەیدانی: سەرکەوتنی چالاکی تەنها بە ژمارە نییە؛ بە سەلامەتی ڕێگا، ڕاستی ڕاپۆرت، ڕەزامەندی سوودمەند و پاراستنی کەرامەت دەپێورێت.',
            f'پوختەی سیاسی و ئەکادیمی: دادپەروەری، شۆرا، پاراستنی ماڵی گشتی و لێپرسینەوەی خود پێش لێپرسینەوەی خەڵک کلیلی چاکسازیی ئارامن.',
        ]
    return variants[(post_number - 1) % len(variants)]


def reel_text(user):
    if is_arabic(user):
        return f'ريل قصير من {user["display"]}: دقيقة واحدة عن {user["title"]}، بلغة هادئة، ومعلومة موثقة، ودعوة إلى عمل نافع بلا تحريض ولا تجريح.'
    return f'ڕیلێکی کورت لە {user["display"]}: یەک خولەک دەربارەی {user["title"]}، بە زمانی ئارام، زانیاری پشتڕاستکراو و بانگەواز بۆ کاری سوودبەخش بەبێ هاندان بۆ توندی.'


def question_payload(user):
    info = theme_info(user["theme"])
    if is_arabic(user):
        title = f'كيف نطبق {user["title"]} في مشروع إسلامي يخدم الناس بكرامة؟'
        body = f'أحتاج جوابا عمليا من أهل الاختصاص. الفكرة الأساسية: {info["ar"]} ما الخطوات، وما مؤشرات النجاح، وما الأخطاء التي يجب تجنبها؟'
        keywords = f'{user["title"]} إسلام أخلاق سياسة خيرية تعليم بحث'
        tags = ["إسلام", "بحث", "أخلاق"] + info["tags"][:2]
    else:
        title = f'چۆن {user["title"]} لە پڕۆژەیەکی ئیسلامی بەکەرامەت جێبەجێ بکەین؟'
        body = f'وەڵامێکی کرداری دەمەوێت لە پسپۆڕان. بیرۆکەی سەرەکی: {info["ckb"]} هەنگاوەکان، نیشاندەرەکانی سەرکەوتن و هەڵەکانی پێویست بە خۆپاراستن چین؟'
        keywords = f'{user["title"]} ئیسلام ڕەوشتی خێرخوازی پەروەردە توێژینەوە'
        tags = ["ئیسڵام", "توێژینەوە", "ڕەوشتی"] + info["tags"][:2]
    return {
        "title": title,
        "body": body,
        "tags": tags,
        "keywords": keywords,
        "answersLocked": False,
        "maxAnswers": 6,
    }


def answer_payload(author_user, question_user):
    info = theme_info(question_user["theme"])
    if is_arabic(author_user):
        body = f'الجواب العملي يبدأ بتحديد الحاجة، ثم اختيار معيار شرعي وأخلاقي، ثم توثيق التنفيذ دون كشف خصوصية الناس. في موضوع {question_user["title"]}: {info["ar"]} والمؤشر الجيد هو أثر واضح مع تقرير مختصر قابل للمراجعة.'
    else:
        body = f'وەڵامی کرداری بە دیاریکردنی پێویستی دەست پێدەکات، پاشان پێوەری شەرعی و ڕەوشتی هەڵدەبژێردرێت، دواتر جێبەجێکردن بەبێ ئاشکراکردنی نهێنی خەڵک تۆمار دەکرێت. لە بابەتی {question_user["title"]}: {info["ckb"]} نیشاندەری باش کاریگەریی ڕوون و ڕاپۆرتێکی کورت و قابل بە پێداچوونەوەیە.'
    return {
        "body": body,
        "links": "https://idreescharity.org/ku/, https://bextewery.org/",
        "sources": [
            {
                "sourceType": "URL",
                "title": "سەرچاوەی چالاکی خێرخوازی / مصدر النشاط الخيري",
                "citationText": "نماذج العمل الخيري والكردي والإسلامي تفيد في بناء بيانات اختبار واقعية.",
                "url": "https://bextewery.org/"
            }
        ]
    }


def research_payload(user, contributor_user):
    info = theme_info(user["theme"])
    if is_arabic(user):
        title = f'دراسة اختبارية حول {user["title"]} في خدمة المجتمع الإسلامي'
        description = f'تبحث هذه الدراسة الاختبارية في {user["title"]} باعتباره مجالا يجمع بين النص الشرعي والمصلحة العامة والتطبيق المؤسسي. تنطلق من قاعدة: {info["ar"]} وتقترح إطارا من خمس مراحل: التشخيص، التحقق، التنفيذ، التقرير، والمراجعة الأخلاقية.'
        abstract = f'ملخص بحثي عن {user["title"]} مع تركيز على الكرامة، والشفافية، والمنهج، والقياس.'
        citation = f'{user["display"]}. (٢٠٢٦). {title}. بيانات اختبارية للمنصة.'
        keywords = f'{user["title"]} إسلام مجتمع أخلاق سياسة خيرية تعليم'
        contribution_note = "مراجعة منهجية ولغوية للنص العربي والكردي."
        source_title = "مصدر عام في العمل الإسلامي والخيري"
        source_citation = "استخدمت الروابط العامة كنماذج مصادر للاختبار وليست اعتمادا رسميا للمحتوى."
    else:
        title = f'توێژینەوەی تاقیکردنەوە دەربارەی {user["title"]} لە خزمەتی کۆمەڵگای ئیسلامی'
        description = f'ئەم توێژینەوەیەی تاقیکردنەوە {user["title"]} وەک بوارێک دەخوێنێتەوە کە دەق، بەرژەوەندی گشتی و جێبەجێکردنی دامەزراوەیی پێکەوە دەبەستێت. بنەمایەکە: {info["ckb"]} چوارچێوەکە پێنج قۆناغ پێشنیار دەکات: دەستنیشانکردن، پشتڕاستکردن، جێبەجێکردن، ڕاپۆرت و پێداچوونەوەی ڕەوشتی.'
        abstract = f'پوختەی توێژینەوە دەربارەی {user["title"]} بە جەخت لەسەر کەرامەت، ڕوونی، میتۆد و پێوانەکردن.'
        citation = f'{user["display"]}. (٢٠٢٦). {title}. داتای تاقیکردنەوەی پلاتفۆرم.'
        keywords = f'{user["title"]} ئیسلام کۆمەڵگا ڕەوشتی سیاسەت خێرخوازی پەروەردە'
        contribution_note = "پێداچوونەوەی میتۆدی و زمانی بۆ دەقی کوردی و عەرەبی."
        source_title = "سەرچاوەی گشتی بۆ کاری ئیسلامی و خێرخوازی"
        source_citation = "بەستەرە گشتییەکان وەک نموونەی سەرچاوە بۆ تاقیکردنەوە بەکارهاتوون، نەک پشتگیری فەرمی."
    return {
        "title": title,
        "description": description,
        "abstractText": abstract,
        "bodyFormat": "MARKDOWN",
        "keywords": keywords,
        "citation": citation,
        "visibility": "PUBLIC",
        "commentsEnabled": True,
        "downloadsEnabled": False,
        "tags": theme_info(user["theme"])["tags"] + ["ئیسڵام"],
        "sources": [
            {
                "sourceType": "URL",
                "title": source_title,
                "citationText": source_citation,
                "url": "https://idreescharity.org/ku/",
                "displayOrder": 1,
            }
        ],
        "contributors": [
            {
                "userId": f'{{{{{variable_name(contributor_user, "Id")}}}}}',
                "role": "REVIEWER",
                "displayOrder": 1,
                "contributionNote": contribution_note,
            }
        ],
    }


def build_users():
    users = []
    for sequence_number, values in enumerate(USERS, start=1):
        code, fname, lname, display, location, title, language, theme = values
        users.append({
            "sequence": sequence_number,
            "code": code,
            "fname": fname,
            "lname": lname,
            "display": display,
            "location": location,
            "title": title,
            "lang": language,
            "theme": theme,
            "username": f'{code}_islamic_{{{{runId}}}}',
            "email": f'{code}.islamic.{{{{runId}}}}@example.com',
        })
    return users


def main():
    users = build_users()
    parts = [
        "# IRC Islamic Community 35-User Seed Data",
        "# Runner: JetBrains HTTP Client / IntelliJ `.http`",
        "# Creates 35 users, profiles, follows, 105 normal posts, 35 reels, 35 QNA threads, 35 researches, comments, reactions, saves, and shares.",
        "# Content is Kurdish Sorani and Arabic. Reels are one per user to stay under the backend social-write rate limiter.",
        "",
        "@baseUrl = http://localhost:8080",
        "@runId = {{$timestamp}}",
        "@password = TestData!2026",
        "",
    ]

    for user in users:
        token_var = variable_name(user, "Token")
        user_id_var = variable_name(user, "Id")
        emit_json_request(
            parts,
            f'{user["sequence"]:03d}. Register user — {user["display"]}',
            "POST",
            "{{baseUrl}}/api/v1/auth/register",
            None,
            {
                "fname": user["fname"],
                "lname": user["lname"],
                "username": user["username"],
                "email": user["email"],
                "password": "{{password}}",
            },
            201,
            [(token_var, "response.body.accessToken"), (user_id_var, "response.body.user.id")],
            ['client.assert(!!response.body.user.id, "Expected registered user id");'],
        )

    for user in users:
        emit_json_request(
            parts,
            f'{100 + user["sequence"]:03d}. Update profile — {user["display"]}',
            "PATCH",
            "{{baseUrl}}/api/v1/users/me/profile",
            variable_name(user, "Token"),
            {
                "displayName": user["display"],
                "profileBio": profile_bio(user),
                "selfDescriber": user["title"],
                "location": user["location"],
                "academicTitle": user["title"],
                "institutionName": "تاقیگەی تاقیکردنەوەی کۆمەڵگای ئیسلامی" if not is_arabic(user) else "مختبر اختبار المجتمع الإسلامي",
                "websiteUrl": "https://idreescharity.org/ku/" if user["sequence"] % 2 else "https://bextewery.org/",
                "isForHire": False,
                "isProfileLocked": False,
                "contentLanguage": user["lang"],
            },
            200,
        )

    for user in users:
        emit_empty_request(
            parts,
            f'{200 + user["sequence"]:03d}. Verify user exists — {user["display"]}',
            "GET",
            f'{{{{baseUrl}}}}/api/v1/users/{{{{{variable_name(user, "Id")}}}}}',
            variable_name(user, "Token"),
            200,
            assertions=[
                'client.assert(!!response.body.id, "Expected user id");',
                'client.assert(!!response.body.username, "Expected username");',
            ],
        )

    for user in users:
        current_index = user["sequence"] - 1
        follow_targets = [
            users[(current_index + 1) % len(users)],
            users[(current_index + 7) % len(users)],
        ]
        for target_index, target_user in enumerate(follow_targets, start=1):
            emit_empty_request(
                parts,
                f'{300 + user["sequence"]:03d}.{target_index} Follow — {user["display"]} follows {target_user["display"]}',
                "POST",
                f'{{{{baseUrl}}}}/api/v1/users/{{{{{variable_name(target_user, "Id")}}}}}/follow',
                variable_name(user, "Token"),
                200,
            )

    for user in users:
        for post_number in range(1, 4):
            post_variable = variable_name(user, f"Post{post_number}")
            image_url = MEDIA_IMAGES[(user["sequence"] + post_number) % len(MEDIA_IMAGES)]
            emit_json_request(
                parts,
                f'{400 + user["sequence"]:03d}.{post_number} Post — {user["display"]}',
                "POST",
                "{{baseUrl}}/api/v1/posts",
                variable_name(user, "Token"),
                {
                    "postType": "EMBEDDED",
                    "visibility": "PUBLIC",
                    "textContent": post_text(user, post_number),
                    "locationName": user["location"],
                    "mediaUrls": [image_url],
                    "mediaTypes": ["IMAGE"],
                },
                200,
                [(post_variable, "response.body.id")],
                [
                    'client.assert(!!response.body.id, "Expected post id");',
                    'client.assert(!!response.body.authorId, "Expected post authorId");',
                ],
            )

        video_url, thumbnail_url = MEDIA_VIDEOS[user["sequence"] % len(MEDIA_VIDEOS)]
        emit_json_request(
            parts,
            f'{500 + user["sequence"]:03d}. Reel — {user["display"]}',
            "POST",
            "{{baseUrl}}/api/v1/posts",
            variable_name(user, "Token"),
            {
                "postType": "REEL",
                "visibility": "PUBLIC",
                "textContent": reel_text(user),
                "locationName": user["location"],
                "mediaUrls": [video_url, thumbnail_url],
                "mediaTypes": ["VIDEO", "IMAGE"],
            },
            200,
            [(variable_name(user, "Reel"), "response.body.id")],
            [
                'client.assert(!!response.body.id, "Expected reel id");',
                'client.assert(response.body.postType === "REEL", "Expected reel postType");',
            ],
        )

    for user in users:
        emit_json_request(
            parts,
            f'{600 + user["sequence"]:03d}. QNA — {user["display"]}',
            "POST",
            "{{baseUrl}}/api/v1/questions",
            variable_name(user, "Token"),
            question_payload(user),
            201,
            [(variable_name(user, "Question"), "response.body.id")],
            [
                'client.assert(!!response.body.id, "Expected question id");',
                'client.assert(!!response.body.authorId, "Expected question authorId");',
            ],
        )

        answer_user = users[user["sequence"] % len(users)]
        emit_json_request(
            parts,
            f'{650 + user["sequence"]:03d}. QNA answer — {answer_user["display"]} answers {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/questions/{{{{{variable_name(user, "Question")}}}}}/answers',
            variable_name(answer_user, "Token"),
            answer_payload(answer_user, user),
            201,
            [(variable_name(user, "Answer"), "response.body.id")],
            ['client.assert(!!response.body.id, "Expected answer id");'],
        )

        emit_empty_request(
            parts,
            f'{700 + user["sequence"]:03d}. Accept QNA answer — {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/questions/{{{{{variable_name(user, "Question")}}}}}/answers/{{{{{variable_name(user, "Answer")}}}}}/accept',
            variable_name(user, "Token"),
            200,
        )

    for user in users:
        contributor_user = users[user["sequence"] % len(users)]
        emit_research_request(
            parts,
            f'{800 + user["sequence"]:03d}. Research — {user["display"]}',
            user,
            research_payload(user, contributor_user),
            201,
            [(variable_name(user, "Research"), "response.body.id")],
        )
        emit_empty_request(
            parts,
            f'{850 + user["sequence"]:03d}. Publish research — {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/researches/{{{{{variable_name(user, "Research")}}}}}/publish',
            variable_name(user, "Token"),
            200,
        )

    for user in users:
        target_user = users[user["sequence"] % len(users)]
        emit_empty_request(
            parts,
            f'{900 + user["sequence"]:03d}. React to next user post — {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/posts/{{{{{variable_name(target_user, "Post1")}}}}}/reactions',
            variable_name(user, "Token"),
            200,
        )
        emit_json_request(
            parts,
            f'{950 + user["sequence"]:03d}. Comment on next user post — {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/posts/{{{{{variable_name(target_user, "Post2")}}}}}/comments',
            variable_name(user, "Token"),
            {
                "text": "پەیامێکی باشە؛ پێویستە ڕوونی، کەرامەت و کاری پێوانەکراو هەمیشە لەگەڵ یەک بن." if not is_arabic(user) else "رسالة نافعة؛ ينبغي أن تبقى الشفافية والكرامة والعمل القابل للقياس معا.",
                "mediaUrl": None,
                "mediaType": None,
            },
            200,
            [(variable_name(user, "Comment"), "response.body.id")],
        )
        emit_empty_request(
            parts,
            f'{1000 + user["sequence"]:03d}. Save next user reel — {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/posts/{{{{{variable_name(target_user, "Reel")}}}}}/saves?collection=Islamic%20Community',
            variable_name(user, "Token"),
            200,
        )
        emit_json_request(
            parts,
            f'{1050 + user["sequence"]:03d}. Share next user post — {user["display"]}',
            "POST",
            f'{{{{baseUrl}}}}/api/v1/posts/{{{{{variable_name(target_user, "Post3")}}}}}/share',
            variable_name(user, "Token"),
            {
                "caption": "هاوبەشکردنی بابەتێکی سوودبەخش بۆ گفتوگۆی ڕەوشتی." if not is_arabic(user) else "مشاركة مادة نافعة للحوار الأخلاقي.",
            },
            200,
        )

    for user in users:
        emit_empty_request(
            parts,
            f'{1100 + user["sequence"]:03d}. Verify user profile — {user["display"]}',
            "GET",
            f'{{{{baseUrl}}}}/api/v1/users/{{{{{variable_name(user, "Id")}}}}}/profile',
            variable_name(user, "Token"),
            200,
            assertions=[
                'client.assert(!!response.body.id, "Expected profile user id");',
                'client.assert(!!response.body.profile, "Expected profile object");',
            ],
        )
        emit_empty_request(
            parts,
            f'{1150 + user["sequence"]:03d}. Verify created post — {user["display"]}',
            "GET",
            f'{{{{baseUrl}}}}/api/v1/posts/{{{{{variable_name(user, "Post1")}}}}}',
            variable_name(user, "Token"),
            200,
            assertions=[
                'client.assert(!!response.body.id, "Expected post id");',
                'client.assert(!!response.body.authorId, "Expected post authorId");',
            ],
        )
        emit_empty_request(
            parts,
            f'{1200 + user["sequence"]:03d}. Verify created QNA — {user["display"]}',
            "GET",
            f'{{{{baseUrl}}}}/api/v1/questions/{{{{{variable_name(user, "Question")}}}}}',
            variable_name(user, "Token"),
            200,
            assertions=[
                'client.assert(!!response.body.id, "Expected question id");',
                'client.assert(!!response.body.authorId, "Expected question authorId");',
            ],
        )
        emit_empty_request(
            parts,
            f'{1250 + user["sequence"]:03d}. Verify created research — {user["display"]}',
            "GET",
            f'{{{{baseUrl}}}}/api/v1/researches/{{{{{variable_name(user, "Research")}}}}}',
            variable_name(user, "Token"),
            200,
            assertions=[
                'client.assert(!!response.body.id, "Expected research id");',
                'client.assert(!!response.body.researcherId, "Expected researcher id");',
            ],
        )

    parts.append("### 1300. Smoke check — reels feed")
    parts.append("GET {{baseUrl}}/api/v1/posts/reels?pageSize=35")
    parts.append("")
    parts.append(emit_response_script(200))
    parts.append("")
    parts.append("")
    parts.append("### 1301. Smoke check — QNA feed")
    parts.append("GET {{baseUrl}}/api/v1/questions?page=0&size=35")
    parts.append("Authorization: Bearer {{u01Token}}")
    parts.append("")
    parts.append(emit_response_script(200))
    parts.append("")
    parts.append("")
    parts.append("### 1302. Smoke check — research feed")
    parts.append("GET {{baseUrl}}/api/v1/researches/feed?page=0&size=35&sort=publishedAt,desc")
    parts.append("Authorization: Bearer {{u02Token}}")
    parts.append("")
    parts.append(emit_response_script(200))
    parts.append("")

    OUTPUT_PATH.write_text("\n".join(parts), encoding="utf-8")


if __name__ == "__main__":
    main()

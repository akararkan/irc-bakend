#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  IRC PLATFORM — ISLAMIC ACADEMIC DEMO SEEDER
#
#  Rich demo data with Islamic academic topics (Quran, Hadith, Fiqh, Aqeedah,
#  Seerah, Islamic finance, ethics). For each of 15 users:
#    • 4 posts (mixed topics, Arabic / English / Kurdish)
#    • 2 published researches (one Quranic-sciences, one Fiqh / contemporary)
#    • 3 Q&A questions
#    • every question receives 2–3 answers from different peers
#    • cross-user engagement: reactions (LIKE-only), saves, top-level comments
#
#  All accounts share the password "11111111".
#
#  Usage:
#    ./scripts/seed-islamic-demo.sh
#    BASE_URL=https://… ./scripts/seed-islamic-demo.sh
#
#  Idempotent: if a user already exists from a previous run, it logs them in.
# ─────────────────────────────────────────────────────────────────────────────

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASSWORD="11111111"

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'
YEL=$'\033[33m'; CYN=$'\033[36m'; RST=$'\033[0m'
step()  { printf "%s▶ %s%s\n"  "$CYN" "$*" "$RST"; }
ok()    { printf "  %s✓%s %s\n" "$GRN" "$RST" "$*"; }
warn()  { printf "  %s!%s %s\n" "$YEL" "$RST" "$*"; }
fail()  { printf "  %s✗%s %s\n" "$RED" "$RST" "$*"; }

api() {
  local method=$1 path=$2 body=${3:-} token=${4:-}
  local hdr=(-H "Content-Type: application/json" -H "Accept: application/json")
  [[ -n "$token" ]] && hdr+=(-H "Authorization: Bearer $token")
  if [[ -n "$body" ]]; then
    curl -sS --max-time 30 -X "$method" "${hdr[@]}" -d "$body" "$BASE_URL$path"
  else
    curl -sS --max-time 30 -X "$method" "${hdr[@]}" "$BASE_URL$path"
  fi
}

# ── 15 demo users (Arabic / English / Kurdish) ───────────────────────────────
USERS=(
  "ahmed_ali|Ahmed|Ali|ahmed.ali@demo.local|ar"
  "fatima_zahra|Fatima|Al-Zahra|fatima.zahra@demo.local|ar"
  "omar_khaled|Omar|Khaled|omar.khaled@demo.local|ar"
  "layla_yousef|Layla|Yousef|layla.yousef@demo.local|ar"
  "noor_hassan|Noor|Hassan|noor.hassan@demo.local|ar"
  "john_smith|John|Smith|john.smith@demo.local|en"
  "emma_watson|Emma|Watson|emma.watson@demo.local|en"
  "michael_brown|Michael|Brown|michael.brown@demo.local|en"
  "sarah_johnson|Sarah|Johnson|sarah.johnson@demo.local|en"
  "david_wilson|David|Wilson|david.wilson@demo.local|en"
  "rebin_karzan|Rebin|Karzan|rebin.karzan@demo.local|ku"
  "shvan_hawar|Shvan|Hawar|shvan.hawar@demo.local|ku"
  "diyar_aram|Diyar|Aram|diyar.aram@demo.local|ku"
  "bnar_dilshad|Bnar|Dilshad|bnar.dilshad@demo.local|ku"
  "ronak_hemin|Ronak|Hemin|ronak.hemin@demo.local|ku"
)

# ═════════════════════════════════════════════════════════════════════════════
#  Content pools — Islamic academic topics
# ═════════════════════════════════════════════════════════════════════════════

# ── POSTS (per language — 4 per user, indexed 0..3) ──────────────────────────
POSTS_AR=(
  "بدأت اليوم مراجعة تفسير سورة البقرة لابن كثير — أعمق ما في هذه السورة هو الترابط بين آيات الأحكام وآيات القصص. ما الذي أعجبكم أكثر في هذه السورة؟"
  "من أجمل ما قرأت في علوم الحديث: ”الحديث الحسن لذاته“ — يحتاج طالب العلم إلى فهم دقيق للفرق بين الصحيح والحسن والضعيف قبل الخوض في الاستدلال."
  "حضرت اليوم محاضرة عن السيرة النبوية، تحديداً عن غزوة بدر — العبرة الكبرى أن النصر بيد الله مهما اختلت موازين القوى."
  "نشرت ورقة جديدة عن مقاصد الشريعة وأثرها في الفقه المعاصر — أرحب بنقاشاتكم وملاحظاتكم العلمية."
  "”إنما الأعمال بالنيات“ — حديث جامع يعيدنا دائماً إلى أصل العمل. كل تخصص أكاديمي يحتاج إلى نية صادقة."
  "أعجبني جداً كتاب ”إحياء علوم الدين“ للإمام الغزالي — كيف وفّق بين الفقه والتصوف بأسلوب علمي رفيع."
  "اليوم في الصف ناقشنا الفرق بين الإجماع والقياس — أيها أقوى في الاستدلال؟ شاركوني آراءكم الأكاديمية."
  "من أراد فهم الاقتصاد الإسلامي فليبدأ بكتاب ”الأموال“ لأبي عبيد القاسم بن سلام — مرجع لا غنى عنه."
)
POSTS_EN=(
  "Started reviewing Ibn Kathir's tafsir of Surat al-Baqarah today — the deepest pattern is how legal verses interlace with narratives. What strikes you most about this surah?"
  "One of the most beautiful concepts in hadith sciences is hasan li-dhatihi — a student of knowledge needs a precise grasp of sahih / hasan / da'if before deriving rulings."
  "Attended a lecture on the Sirah today, specifically the battle of Badr — the key lesson: victory is from Allah alone, regardless of material balance."
  "Published a new paper on Maqasid al-Shari'ah and its impact on contemporary fiqh — I welcome scholarly critique and discussion."
  "”Actions are by intentions“ — a comprehensive hadith that returns us to the origin of every deed. Every academic field needs sincere intent."
  "Really enjoying al-Ghazali's Ihya' Ulum al-Din — how elegantly he reconciles fiqh and tasawwuf with academic rigor."
  "In class today we discussed the difference between ijma' and qiyas — which is stronger in legal reasoning? Share your scholarly views."
  "Anyone wanting to study Islamic economics should start with Abu Ubayd al-Qasim ibn Sallam's Kitab al-Amwal — an indispensable primary source."
)
POSTS_KU=(
  "ئەمڕۆ دەستم کرد بە پێداچوونەوەی تەفسیری ئیبن کەسیر بۆ سورەتی بەقەرە — وردترین چیز پەیوەندی نێوان ئایەتی حوکمەکان و چیرۆکەکانە. چی زۆرتر سەرنجی ڕاکێشاون؟"
  "یەکێک لە جوانترین چەمکی زانستی حەدیس ”حسن لذاتە“یە — قوتابی زانست پێویستی بە تێگەیشتنێکی ووردە لە نێوان سەحیح و حەسەن و زەعیف پێش دەرهێنانی حوکم."
  "ئەمڕۆ ئامادەی وانەی سیرەی پێغەمبەرم بوو، تایبەت بە غەزوەی بەدر — وانە گەورەکە ئەوەیە کە سەرکەوتن لە لای خوداوەندە، چیتر مادە چەند کەم بێت."
  "نووسراوێکی نوێم بڵاوکردەوە سەبارەت بە مەقاسیدی شەریعە و کاریگەرییەکانی لە فیقهی هاوچەرخدا — چاوەڕێی ڕەخنە و گفتوگۆی ئەکادیمی دەکەم."
  "”تەنها کردارەکان بە نیەتەکانن“ — حەدیسێکی گشتگیر کە دەمانگەڕێنێتەوە بۆ بنەڕەتی هەر کارێک. هەر بواری ئەکادیمی پێویستی بە نیەتی پاکە."
  "زۆرم پێخۆشە کتێبی ”ئیحیای عولوم الدین“ی ئیمام غەزالی — چۆن بە شێوەیەکی زانستی فیقه و تەسەوف یەکدەخات."
  "ئەمڕۆ لە پۆلدا گفتوگۆمان لەسەر جیاوازی نێوان ئیجماع و قیاس کرد — کامەیان لە دەلیلهێنانەوەدا بەهێزترە؟ بۆچوونتانم پێبڵێن."
  "هەرکەسێک بیەوێت ئابووری ئیسلامی بخوێنێت با لە ”کتاب الأموال“ی ئەبو عوبەید دەست پێبکات — سەرچاوەیەکی بنەڕەتی پێویست."
)

# ── COMMENTS (used for engagement) ──────────────────────────────────────────
COMMENT_AR=(
  "بحث قيم بارك الله فيك، أضفت ملاحظة في تعليقي."
  "ما شاء الله — أقترح إضافة مرجع للإمام الشاطبي في الموافقات."
  "أرى أن النقطة الثالثة تحتاج إلى تفصيل أكبر، خصوصاً في باب المعاملات."
)
COMMENT_EN=(
  "Valuable work, baraka Allahu fik — I added a note in my comment."
  "MashaAllah — I'd suggest adding a reference to al-Shatibi's al-Muwafaqat."
  "I think your third point needs more elaboration, especially in mu'amalat."
)
COMMENT_KU=(
  "توێژینەوەیەکی بەنرخە، خوا لێت ڕازی بێت — تێبینییەکم زیادکرد."
  "ماشاءاللە — پێشنیار دەکەم سەرچاوەی ئیمام شاتیبی لە الموافقات زیاد بکرێت."
  "بڕوام وایە خاڵی سێیەم پێویستی بە ووردبینی زیاترە، بەتایبەتی لە مووعامەلات."
)

# ── RESEARCHES (each user publishes 2; matched by lang + index 0/1) ──────────
RES_TITLE_AR=(
  "مقاصد الشريعة الإسلامية وأثرها في الفقه المعاصر"
  "منهج المحدثين في التعامل مع الأحاديث المتعارضة"
)
RES_TITLE_EN=(
  "Maqasid al-Shari'ah and its Impact on Contemporary Islamic Jurisprudence"
  "Methodology of the Muhaddithun in Reconciling Conflicting Hadith"
)
RES_TITLE_KU=(
  "مەقاسیدی شەریعەی ئیسلامی و کاریگەرییەکانی لە فیقهی هاوچەرخدا"
  "میتۆدی محەدیسەکان لە مامەڵەکردن لەگەڵ حەدیسە جیاوازەکاندا"
)

RES_DESC_AR=(
  "تتناول هذه الدراسة المقاصد الكلية للشريعة الإسلامية بدءاً من تأصيل الإمام الشاطبي في الموافقات، مروراً بتطورها عند العلماء المعاصرين كابن عاشور والريسوني، وانتهاءً بتطبيقاتها في النوازل الفقهية الحديثة من معاملات مصرفية وقضايا طبية حيوية. تعتمد المنهجية على الجمع بين الاستقراء النصي والتحليل الأصولي."
  "تبحث هذه الدراسة في مناهج المحدثين في التعامل مع الأحاديث المتعارضة ظاهراً، عبر مدارس الجمع والترجيح والنسخ والتوقف. تستعرض الدراسة نماذج تطبيقية من صحيح البخاري ومسلم، ثم تقارن منهج الإمام الشافعي بمنهج الإمام أحمد، وتستخلص قواعد عملية لطلاب علم الحديث."
)
RES_DESC_EN=(
  "This study examines the universal objectives (maqasid) of Islamic shari'ah starting from al-Shatibi's foundational work in al-Muwafaqat, through their development by contemporary scholars such as Ibn Ashur and al-Raysuni, to applications in modern nawazil — Islamic banking and bioethics. The methodology combines textual induction with usuli analysis."
  "This paper investigates the methods of the muhaddithun in reconciling apparently conflicting hadith through the schools of jam', tarjih, naskh, and tawaqquf. It surveys applied examples from Sahih al-Bukhari and Muslim, compares al-Shafi'i's method with that of Imam Ahmad, and distills practical rules for students of hadith."
)
RES_DESC_KU=(
  "ئەم لێکۆڵینەوەیە مەقاسیدی گشتی شەریعەی ئیسلامی لێکدەداتەوە، دەستپێکی لە بنەماکانی ئیمام شاتیبی لە الموافقاتە، تێپەڕ بە گەشەسەندنی لای زانایانی هاوچەرخ وەک ئیبن عاشور و ڕەیسوونی، تا گەیشتن بە بەکارهێنانی لە نوازلی فیقهی نوێ — مووعامەلاتی بانکی و کۆمەڵگەی پزیشکی. میتۆدەکە کۆکردنەوەی ئەستقراء نووسراوی و شیکارییە ئوسوولییە."
  "ئەم توێژینەوەیە لێکۆڵینەوەی میتۆدی محەدیسەکانە لە مامەڵەکردن لەگەڵ حەدیسە بەرگوەکڕاو، لە ڕێگەی قوتابخانەکانی جەمع، تەرجیح، نەسخ و تەواقف. نموونەی پراکتیکی لە سەحیحی بوخاری و مسلم پێشکەش دەکات، میتۆدی ئیمام شافیعی بەراورد دەکات لەگەڵ ئیمام ئەحمەد، و یاسای پراکتیکی بۆ قوتابیانی زانستی حەدیس دەردەهێنێت."
)

RES_ABS_AR=(
  "ملخص: نقدم في هذا البحث رؤية أصولية لمقاصد الشريعة وكيف توظف في النوازل الحديثة، مع تطبيقات على المعاملات المالية والقضايا الطبية."
  "ملخص: نتناول مناهج العلماء في الجمع بين الأحاديث المتعارضة مع نماذج تطبيقية من الصحيحين."
)
RES_ABS_EN=(
  "Abstract: We present a usuli view of maqasid and how to apply them to contemporary nawazil — finance and bioethics — with worked examples."
  "Abstract: We survey scholarly methods for harmonizing apparently conflicting hadith with applied examples from the two Sahihs."
)
RES_ABS_KU=(
  "پوختە: لێرەدا تێڕوانینێکی ئوسووولی پێشکەش دەکەین بۆ مەقاسید و چۆن لە نوازلی هاوچەرخ بەکار بهێنرێن — مووعامەلاتی دارایی و کۆمەڵگەی پزیشکی."
  "پوختە: میتۆدی زانایان دەخوێنینەوە بۆ کۆکردنەوەی حەدیسە بەرگوەکڕاوەکان لەگەڵ نموونەی پراکتیکی لە سەحیحەکان."
)

RES_TAGS_AR='["maqasid","fiqh","usul","arabic","islamic-studies"]'
RES_TAGS_EN='["maqasid","fiqh","usul","english","islamic-studies"]'
RES_TAGS_KU='["maqasid","fiqh","usul","kurdish","islamic-studies"]'

RES_KW_AR=(
  "مقاصد الشريعة, الفقه المعاصر, النوازل, الاقتصاد الإسلامي, الشاطبي"
  "علم الحديث, الأحاديث المتعارضة, الجمع والترجيح, البخاري, مسلم"
)
RES_KW_EN=(
  "Maqasid al-Shari'ah, Contemporary Fiqh, Nawazil, Islamic Finance, al-Shatibi"
  "Hadith Sciences, Conflicting Hadith, Jam' and Tarjih, Bukhari, Muslim"
)
RES_KW_KU=(
  "مەقاسیدی شەریعە, فیقهی هاوچەرخ, نوازل, ئابووری ئیسلامی, شاتیبی"
  "زانستی حەدیس, حەدیسە بەرگوەکڕاو, جەمع و تەرجیح, بوخاری, مسلم"
)

# ── QUESTIONS (3 per user — indexed by lang) ─────────────────────────────────
Q_TITLE_AR=(
  "كيف نوفّق بين القياس الأصولي والاجتهاد المعاصر في النوازل المالية؟"
  "ما الفرق العملي بين السنة التقريرية والسنة الفعلية في الاستدلال الفقهي؟"
  "هل يعد التشريع المقاصدي بديلاً عن الفقه التقليدي أم متمماً له؟"
  "ما حكم العملات الرقمية في الفقه الإسلامي المعاصر؟"
  "كيف نقرأ السيرة النبوية في ضوء التحديات الأكاديمية الحديثة؟"
  "ما المنهج العلمي الأمثل لدراسة علوم القرآن للمبتدئين؟"
)
Q_BODY_AR=(
  "أبحث عن مراجع تجمع بين الأصول الكلاسيكية وتطبيقاتها في القضايا المعاصرة. شاركوني آراءكم."
  "أحتاج إلى أمثلة من كتب الحديث المعتمدة، مع التفريق بين القولية والفعلية والتقريرية."
  "في الجامعة قرأت آراء متباينة — أريد منكم جمع المراجع الأكاديمية."
  "كنت قد بدأت قراءة بحث للمجمع الفقهي، أريد آراءكم الأكاديمية."
  "كيف نطبق المنهج التاريخي النقدي مع احترام الأصول الشرعية؟"
  "أنصحوني بقائمة كتب ومسارات دراسية للمبتدئين، خصوصاً في باب علم القراءات."
)
Q_TITLE_EN=(
  "How do we reconcile classical qiyas with contemporary ijtihad in financial nawazil?"
  "What is the practical difference between sunnah taqririyyah and sunnah fi'liyyah in legal reasoning?"
  "Is maqasid-based legislation a replacement for traditional fiqh, or a complement to it?"
  "What is the ruling on digital currencies in contemporary Islamic jurisprudence?"
  "How should we read the Sirah in light of modern academic critique?"
  "What is the best methodology for beginners to study ulum al-Quran?"
)
Q_BODY_EN=(
  "Looking for references combining classical usul with applications to modern issues. Share your views."
  "I need worked examples from canonical hadith literature, distinguishing qawliyyah, fi'liyyah and taqririyyah."
  "At university I've read contradictory views — please share academic references."
  "I started a paper from the Fiqh Academy and want scholarly opinions."
  "How do we apply the historical-critical method while respecting shar'i sources?"
  "Please recommend a book list and study path for beginners, especially in qira'at."
)
Q_TITLE_KU=(
  "چۆن قیاسی ئوسوولی و ئیجتیهادی هاوچەرخ کۆدەکەینەوە لە نوازلی دارایی؟"
  "جیاوازی پراکتیکی نێوان سوننەی تەقریری و سوننەی فیعلی لە دەلیلی فیقهیدا چییە؟"
  "ئایا یاسادانانی مەقاسیدی جێگرەوەی فیقهی نەریتییە یان تەواوکارییەتی؟"
  "حوکمی دراوە دیجیتاڵییەکان لە فیقهی ئیسلامی هاوچەرخدا چییە؟"
  "چۆن سیرەی پێغەمبەر بخوێنینەوە لە ڕووناکی ڕەخنەی ئەکادیمی نوێدا؟"
  "باشترین میتۆد چییە بۆ سەرەتاکان بۆ خوێندنی عوولوومی قورئان؟"
)
Q_BODY_KU=(
  "بەدوای سەرچاوەدا دەگەڕێم کە ئوسووولی کلاسیکی و بەکارهێنانی لە کاروباری هاوچەرخ کۆبکاتەوە."
  "نموونەی پراکتیکی لە کتێبی پەسەندکراوی حەدیس پێویستە، لەگەڵ جیاکردنەوەی قوولی، فیعلی و تەقریری."
  "لە زانکۆدا بیروڕای جیاوازم خوێندووە — تکایە سەرچاوەی ئەکادیمی پێبڵێن."
  "دەستم کرد بە خوێندنەوەی توێژینەوەی مەجمەعی فیقهی، بۆچوونی ئەکادیمیتانم پێویستە."
  "چۆن میتۆدی مێژوویی ڕەخنەیی بەکار دەهێنین لەگەڵ ڕێزگرتن لە سەرچاوە شەرعییەکان؟"
  "تکایە لیستی کتێب و ڕێگەی خوێندن بۆ سەرەتاکان پێشنیار بکەن، بەتایبەتی لە قیراءات."
)

# ── ANSWERS (each question gets 2–3 from peers) ──────────────────────────────
ANS_AR=(
  "أرى أن المرجع الأصلي هو ”الموافقات“ للشاطبي مع ”نظرية المقاصد“ لابن عاشور — أنصح بقراءتهما معاً."
  "في رأيي العلمي، لا تعارض بين الأصول والاجتهاد المعاصر إذا فهمنا المقاصد فهماً صحيحاً وتعاملنا مع النص بطريقة شمولية."
  "أنصح بمراجعة كتاب ”تيسير علم أصول الفقه“ للجديع — كتاب أكاديمي ومنهجي."
  "هذه المسألة تحتاج إلى تأصيل قبل التطبيق — ابدأ بـ ”الإحكام“ للآمدي."
  "أتفق مع الإخوة — وأضيف أن ”مقاصد الشريعة“ للريسوني مرجع تطبيقي معاصر ممتاز."
)
ANS_EN=(
  "I think the canonical reference is al-Shatibi's al-Muwafaqat alongside Ibn Ashur's Maqasid theory — read them together."
  "In my academic view, there's no real contradiction between classical usul and contemporary ijtihad if the maqasid are grasped correctly."
  "I recommend al-Judai's Taysir Usul al-Fiqh — accessible academic methodology."
  "This question needs grounding in usul before application — start with al-Amidi's al-Ihkam."
  "Agree with the brothers/sisters above — al-Raysuni's Maqasid al-Shari'ah is also an excellent contemporary applied reference."
)
ANS_KU=(
  "بڕوام وایە سەرچاوەی بنەڕەتی ”الموافقات“ی شاتیبییە لەگەڵ ”نظریة المقاصد“ی ئیبن عاشور — بە هاوبەشی بخوێنرێنەوە."
  "بە بۆچوونی ئەکادیمی من، هیچ بەرگوەکڕانێک نییە لە نێوان ئوسوول و ئیجتیهادی هاوچەرخدا ئەگەر مەقاسیدەکە بە دروستی تێبگرین."
  "پێشنیار دەکەم ”تیسیر علم أصول الفقه“ی جودیع بخوێنرێتەوە — کتێبێکی ئەکادیمی و میتۆدییە."
  "ئەم پرسیارە پێش بەکارهێنان پێویستی بە بنیاتنانی ئوسوولییە — لە ”الإحكام“ی ئامیدییەوە دەستپێبکە."
  "هاوڕام لەگەڵ خوشک و برایان — ”مقاصد الشريعة“ی ڕەیسوونی سەرچاوەیەکی هاوچەرخ و باشە."
)

pool_pick() {
  # pool_pick <lang> <ar-arr> <en-arr> <ku-arr> <idx>
  local lang=$1 idx=$5 ar=$2 en=$3 ku=$4
  case "$lang" in
    ar) eval "echo \"\${$ar[$idx]}\"" ;;
    en) eval "echo \"\${$en[$idx]}\"" ;;
    ku) eval "echo \"\${$ku[$idx]}\"" ;;
  esac
}

# ═════════════════════════════════════════════════════════════════════════════
#  Working state
# ═════════════════════════════════════════════════════════════════════════════
declare -a TOKENS USERIDS USERNAMES FNAMES LNAMES EMAILS LANGS
declare -a POST_COUNT RESEARCH_COUNT QNA_COUNT ANSWER_COUNT
declare -a POST_IDS QUESTION_IDS QUESTION_OWNERS RESEARCH_IDS

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 1 — register / login
# ═════════════════════════════════════════════════════════════════════════════
step "Authenticating 15 users (password = $PASSWORD)"
for entry in "${USERS[@]}"; do
  IFS='|' read -r username fname lname email lang <<<"$entry"

  body=$(jq -nc \
    --arg u "$username" --arg f "$fname" --arg l "$lname" \
    --arg e "$email"   --arg p "$PASSWORD" \
    '{username:$u, fname:$f, lname:$l, email:$e, password:$p}')

  resp=$(api POST /api/v1/auth/register "$body")
  token=$(jq -r '.accessToken // empty' <<<"$resp")
  if [[ -z "$token" ]]; then
    login=$(jq -nc --arg u "$username" --arg p "$PASSWORD" \
      '{username:$u, password:$p}')
    resp=$(api POST /api/v1/auth/login "$login")
    token=$(jq -r '.accessToken // empty' <<<"$resp")
  fi
  uid=$(jq -r '.user.id // empty' <<<"$resp")

  if [[ -z "$token" ]]; then
    fail "$username — auth failed"
    TOKENS+=(""); USERIDS+=("")
  else
    TOKENS+=("$token"); USERIDS+=("$uid")
    ok "$username — ${fname} ${lname} (id=${uid:0:8}…)"
  fi
  USERNAMES+=("$username"); FNAMES+=("$fname"); LNAMES+=("$lname")
  EMAILS+=("$email"); LANGS+=("$lang")
  POST_COUNT+=(0); RESEARCH_COUNT+=(0); QNA_COUNT+=(0); ANSWER_COUNT+=(0)
done

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 2 — profile bios (Islamic academic)
# ═════════════════════════════════════════════════════════════════════════════
step "Updating profiles with Islamic-academic bios"
bio_ar="باحث في الدراسات الإسلامية — جامعة الأزهر، تخصص الفقه والأصول"
bio_en="Researcher in Islamic Studies — focusing on Fiqh, Usul, and Maqasid"
bio_ku="توێژەری لێکۆڵینەوەی ئیسلامی — تایبەتمەند لە فیقه، ئوسوول و مەقاسید"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  case "${LANGS[$i]}" in
    ar) bio=$bio_ar; loc="القاهرة، مصر" ;;
    en) bio=$bio_en; loc="Cairo / Oxford" ;;
    ku) bio=$bio_ku; loc="سلێمانی، کوردستان" ;;
  esac
  body=$(jq -nc --arg b "$bio" --arg l "$loc" '{bio:$b, location:$l}')
  api PATCH /api/v1/users/me "$body" "${TOKENS[$i]}" >/dev/null
done
ok "bios applied"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 3 — 4 posts per user
# ═════════════════════════════════════════════════════════════════════════════
step "Creating 4 posts per user (60 total) — Islamic topics"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  base=$(( (i * 4) % 8 ))   # rotate through the 8 topic options
  for k in 0 1 2 3; do
    idx=$(( (base + k) % 8 ))
    case "${LANGS[$i]}" in
      ar) txt=${POSTS_AR[$idx]} ;;
      en) txt=${POSTS_EN[$idx]} ;;
      ku) txt=${POSTS_KU[$idx]} ;;
    esac
    body=$(jq -nc --arg t "$txt" \
      '{postType:"TEXT", visibility:"PUBLIC", textContent:$t}')
    resp=$(api POST /api/v1/posts "$body" "${TOKENS[$i]}")
    pid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$pid" ]]; then
      POST_IDS+=("$pid|$i")
      POST_COUNT[$i]=$(( POST_COUNT[$i] + 1 ))
    else
      warn "post failed for ${USERNAMES[$i]}: $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "${#POST_IDS[@]} posts created"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 4 — 2 published researches per user
# ═════════════════════════════════════════════════════════════════════════════
step "Publishing 2 researches per user (30 total)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  for k in 0 1; do
    case "${LANGS[$i]}" in
      ar) title=${RES_TITLE_AR[$k]}; desc=${RES_DESC_AR[$k]}; abs=${RES_ABS_AR[$k]}
          kw=${RES_KW_AR[$k]}; tags=$RES_TAGS_AR ;;
      en) title=${RES_TITLE_EN[$k]}; desc=${RES_DESC_EN[$k]}; abs=${RES_ABS_EN[$k]}
          kw=${RES_KW_EN[$k]}; tags=$RES_TAGS_EN ;;
      ku) title=${RES_TITLE_KU[$k]}; desc=${RES_DESC_KU[$k]}; abs=${RES_ABS_KU[$k]}
          kw=${RES_KW_KU[$k]}; tags=$RES_TAGS_KU ;;
    esac
    data=$(jq -nc --arg t "$title" --arg d "$desc" --arg a "$abs" \
                  --arg k "$kw" --argjson tg "$tags" \
      '{title:$t, description:$d, abstractText:$a, keywords:$k,
        visibility:"PUBLIC", commentsEnabled:true, downloadsEnabled:true,
        tags:$tg}')
    resp=$(curl -sS --max-time 30 \
      -H "Authorization: Bearer ${TOKENS[$i]}" \
      -F "data=$data;type=application/json" \
      "$BASE_URL/api/v1/researches")
    rid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$rid" ]]; then
      pub=$(api POST "/api/v1/researches/$rid/publish" "" "${TOKENS[$i]}")
      status=$(jq -r '.status // empty' <<<"$pub")
      if [[ "$status" == "PUBLISHED" ]]; then
        RESEARCH_IDS+=("$rid|$i")
        RESEARCH_COUNT[$i]=$(( RESEARCH_COUNT[$i] + 1 ))
      else
        warn "publish failed (${USERNAMES[$i]}): $(jq -c '.message // .' <<<"$pub" 2>/dev/null)"
      fi
    else
      warn "research create failed (${USERNAMES[$i]}): $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "${#RESEARCH_IDS[@]} researches published"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 5 — 3 Q&A questions per user (each gets 2–3 peer answers)
# ═════════════════════════════════════════════════════════════════════════════
step "Opening 3 questions per user (45 total)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  base=$(( (i * 3) % 6 ))
  for k in 0 1 2; do
    idx=$(( (base + k) % 6 ))
    case "${LANGS[$i]}" in
      ar) qt=${Q_TITLE_AR[$idx]}; qb=${Q_BODY_AR[$idx]} ;;
      en) qt=${Q_TITLE_EN[$idx]}; qb=${Q_BODY_EN[$idx]} ;;
      ku) qt=${Q_TITLE_KU[$idx]}; qb=${Q_BODY_KU[$idx]} ;;
    esac
    body=$(jq -nc --arg t "$qt" --arg b "$qb" \
      '{title:$t, body:$b, answersLocked:false}')
    resp=$(api POST /api/v1/questions "$body" "${TOKENS[$i]}")
    qid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$qid" ]]; then
      QUESTION_IDS+=("$qid")
      QUESTION_OWNERS+=("$i")
      QNA_COUNT[$i]=$(( QNA_COUNT[$i] + 1 ))
    else
      warn "question failed (${USERNAMES[$i]}): $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "${#QUESTION_IDS[@]} questions opened"

step "Posting 2–3 answers per question from different peers (~110 answers)"
for k in "${!QUESTION_IDS[@]}"; do
  qid=${QUESTION_IDS[$k]}
  owner=${QUESTION_OWNERS[$k]}
  # answer count: 2 for two-thirds of questions, 3 for the rest
  n_answers=$(( (k % 3 == 0) ? 3 : 2 ))
  for a in $(seq 1 "$n_answers"); do
    responder=$(( (owner + a) % ${#USERS[@]} ))
    [[ "$responder" -eq "$owner" || -z "${TOKENS[$responder]}" ]] && \
        responder=$(( (responder + 1) % ${#USERS[@]} ))
    [[ "$responder" -eq "$owner" || -z "${TOKENS[$responder]}" ]] && continue

    ans_idx=$(( (k + a) % 5 ))
    case "${LANGS[$responder]}" in
      ar) txt=${ANS_AR[$ans_idx]} ;;
      en) txt=${ANS_EN[$ans_idx]} ;;
      ku) txt=${ANS_KU[$ans_idx]} ;;
    esac
    body=$(jq -nc --arg b "$txt" '{body:$b}')
    resp=$(api POST "/api/v1/questions/$qid/answers" "$body" "${TOKENS[$responder]}")
    aid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$aid" ]]; then
      ANSWER_COUNT[$responder]=$(( ANSWER_COUNT[$responder] + 1 ))
    fi
  done
done
total_ans=0
for n in "${ANSWER_COUNT[@]}"; do total_ans=$(( total_ans + n )); done
ok "$total_ans answers posted"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 6 — engagement (reactions, saves, comments on posts)
# ═════════════════════════════════════════════════════════════════════════════
step "Cross-user engagement on posts (LIKE + save + one comment per peer)"
react_count=0
save_count=0
comment_count=0
for item in "${POST_IDS[@]}"; do
  IFS='|' read -r pid owner <<<"$item"
  # 4 random-ish reactors (next 4 users circularly, skipping owner)
  for off in 1 2 3 4 5; do
    r=$(( (owner + off) % ${#USERS[@]} ))
    [[ "$r" -eq "$owner" || -z "${TOKENS[$r]}" ]] && continue

    code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
        -H "Authorization: Bearer ${TOKENS[$r]}" \
        -H "Content-Type: application/json" \
        "$BASE_URL/api/v1/posts/$pid/react")
    [[ "$code" =~ ^20 ]] && react_count=$(( react_count + 1 ))
  done

  # 2 savers
  for off in 2 3; do
    s=$(( (owner + off) % ${#USERS[@]} ))
    [[ "$s" -eq "$owner" || -z "${TOKENS[$s]}" ]] && continue
    code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
        -H "Authorization: Bearer ${TOKENS[$s]}" \
        "$BASE_URL/api/v1/posts/$pid/save")
    [[ "$code" =~ ^20 ]] && save_count=$(( save_count + 1 ))
  done

  # 1 commenter
  c=$(( (owner + 6) % ${#USERS[@]} ))
  [[ "$c" -eq "$owner" || -z "${TOKENS[$c]}" ]] && c=$(( (c + 1) % ${#USERS[@]} ))
  case "${LANGS[$c]}" in
    ar) ct=${COMMENT_AR[$(( RANDOM % ${#COMMENT_AR[@]} ))]} ;;
    en) ct=${COMMENT_EN[$(( RANDOM % ${#COMMENT_EN[@]} ))]} ;;
    ku) ct=${COMMENT_KU[$(( RANDOM % ${#COMMENT_KU[@]} ))]} ;;
  esac
  body=$(jq -nc --arg t "$ct" '{textContent:$t}')
  code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
      -H "Authorization: Bearer ${TOKENS[$c]}" \
      -H "Content-Type: application/json" \
      -d "$body" \
      "$BASE_URL/api/v1/posts/$pid/comments")
  [[ "$code" =~ ^20 ]] && comment_count=$(( comment_count + 1 ))
done
ok "$react_count reactions • $save_count saves • $comment_count post comments"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 7 — engagement on researches (LIKE + save)
# ═════════════════════════════════════════════════════════════════════════════
step "Cross-user engagement on researches (LIKE + save)"
r_react=0; r_save=0
for item in "${RESEARCH_IDS[@]}"; do
  IFS='|' read -r rid owner <<<"$item"
  for off in 1 2 3 4 5; do
    u=$(( (owner + off) % ${#USERS[@]} ))
    [[ "$u" -eq "$owner" || -z "${TOKENS[$u]}" ]] && continue
    code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
        -H "Authorization: Bearer ${TOKENS[$u]}" \
        -H "Content-Type: application/json" \
        "$BASE_URL/api/v1/researches/$rid/reactions")
    [[ "$code" =~ ^20 ]] && r_react=$(( r_react + 1 ))
  done
  for off in 1 2; do
    u=$(( (owner + off) % ${#USERS[@]} ))
    [[ "$u" -eq "$owner" || -z "${TOKENS[$u]}" ]] && continue
    code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
        -H "Authorization: Bearer ${TOKENS[$u]}" \
        "$BASE_URL/api/v1/researches/$rid/save")
    [[ "$code" =~ ^20 ]] && r_save=$(( r_save + 1 ))
  done
done
ok "$r_react research-likes • $r_save research-saves"

# ═════════════════════════════════════════════════════════════════════════════
#  SUMMARY
# ═════════════════════════════════════════════════════════════════════════════
echo
printf "%s═══ ISLAMIC ACADEMIC DEMO (password = %s) ═══════════════════════════════════════%s\n" "$BOLD" "$PASSWORD" "$RST"
printf "%-3s %-18s %-22s %-32s %-4s %-5s %-5s %-4s %-4s\n" \
  "#" "username" "full name" "email" "lang" "posts" "rsrch" "qst" "ans"
printf "%s%s%s\n" "$DIM" "$(printf '─%.0s' {1..107})" "$RST"
for i in "${!USERS[@]}"; do
  printf "%-3s %-18s %-22s %-32s %-4s %-5s %-5s %-4s %-4s\n" \
    "$((i+1))" "${USERNAMES[$i]}" "${FNAMES[$i]} ${LNAMES[$i]}" \
    "${EMAILS[$i]}" "${LANGS[$i]}" \
    "${POST_COUNT[$i]}" "${RESEARCH_COUNT[$i]}" \
    "${QNA_COUNT[$i]}" "${ANSWER_COUNT[$i]}"
done
echo
tp=0; tr=0; tq=0
for n in "${POST_COUNT[@]}";     do tp=$(( tp + n )); done
for n in "${RESEARCH_COUNT[@]}"; do tr=$(( tr + n )); done
for n in "${QNA_COUNT[@]}";      do tq=$(( tq + n )); done

printf "%sTotals%s   %s posts • %s researches • %s questions • %s answers\n" \
  "$BOLD" "$RST" "$tp" "$tr" "$tq" "$total_ans"
printf "%sEngagement%s  posts: %s likes / %s saves / %s comments  •  researches: %s likes / %s saves\n" \
  "$BOLD" "$RST" "$react_count" "$save_count" "$comment_count" "$r_react" "$r_save"

echo
echo "Login example:"
echo "  curl -X POST $BASE_URL/api/v1/auth/login \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"username\":\"ahmed_ali\",\"password\":\"$PASSWORD\"}'"

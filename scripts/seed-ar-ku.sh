#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  IRC PLATFORM — RICH KURDISH + ARABIC SEEDER
#
#  Seeds many entities for every Kurdish / Arabic demo persona:
#    • registration + login (idempotent — re-logs in existing users)
#    • profile (bio, location, academicTitle, institutionName, websiteUrl)
#    • follow graph (each user follows ~6 peers + close-friends slice)
#    • posts — both TEXT and IMAGE (real picsum.photos URLs) + #hashtags
#    • stories — PHOTO with real media URLs + 2-option poll on a few
#    • researches — title/abstract/tags + publish + cover image upload
#    • Q&A — questions + 2–3 peer answers + accept one
#    • sounds — sound library entries
#    • reactions (LIKE), saves, comments + replies
#    • highlights from existing stories
#    • a few hashtag-only posts for the trending feed
#
#  Real images come from https://picsum.photos/seed/<seed>/<w>/<h>
#  which returns a deterministic JPEG — no API key, no rate limit.
#
#  All accounts share the password "11111111".
#
#  Usage:
#    ./scripts/seed-ar-ku.sh
#    BASE_URL=http://localhost:8080 ./scripts/seed-ar-ku.sh
# ─────────────────────────────────────────────────────────────────────────────

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASSWORD="11111111"

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'
YEL=$'\033[33m'; CYN=$'\033[36m'; MAG=$'\033[35m'; RST=$'\033[0m'
step()  { printf "\n%s▶ %s%s\n"  "$CYN" "$*" "$RST"; }
ok()    { printf "  %s✓%s %s\n"  "$GRN" "$RST" "$*"; }
warn()  { printf "  %s!%s %s\n"  "$YEL" "$RST" "$*"; }
fail()  { printf "  %s✗%s %s\n"  "$RED" "$RST" "$*"; }
note()  { printf "  %s· %s%s\n"  "$DIM" "$*" "$RST"; }

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

# Quiet status-code-only POST (used for reactions/saves where we only care about success).
api_code() {
  local method=$1 path=$2 token=$3
  curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X "$method" \
      -H "Authorization: Bearer $token" \
      -H "Content-Type: application/json" \
      "$BASE_URL$path"
}

# ═════════════════════════════════════════════════════════════════════════════
#  USERS — 24 personas (12 Arabic, 12 Kurdish)
#  Format: username|fname|lname|email|lang
# ═════════════════════════════════════════════════════════════════════════════
USERS=(
  # ─── Arabic personas ───
  "ahmed_alazhari|أحمد|الأزهري|ahmed.alazhari@demo.local|ar"
  "fatima_alzahra|فاطمة|الزهراء|fatima.alzahra@demo.local|ar"
  "omar_alkhaled|عمر|الخالد|omar.alkhaled@demo.local|ar"
  "layla_alyousef|ليلى|اليوسف|layla.alyousef@demo.local|ar"
  "noor_alhassan|نور|الحسن|noor.alhassan@demo.local|ar"
  "yusuf_almansoor|يوسف|المنصور|yusuf.almansoor@demo.local|ar"
  "khadija_alanbari|خديجة|الأنباري|khadija.alanbari@demo.local|ar"
  "ibrahim_alsalafi|إبراهيم|السلفي|ibrahim.alsalafi@demo.local|ar"
  "aisha_alqurashi|عائشة|القرشي|aisha.alqurashi@demo.local|ar"
  "musab_alrashidi|مصعب|الرشيدي|musab.alrashidi@demo.local|ar"
  "maryam_aljanabi|مريم|الجنابي|maryam.aljanabi@demo.local|ar"
  "abdullah_alfarsi|عبدالله|الفارسي|abdullah.alfarsi@demo.local|ar"
  # ─── Kurdish personas ───
  "rebin_karzan|ڕێبین|کارزان|rebin.karzan@demo.local|ku"
  "shvan_hawar|شڤان|هاوار|shvan.hawar@demo.local|ku"
  "diyar_aram|دیار|ئارام|diyar.aram@demo.local|ku"
  "bnar_dilshad|بنار|دڵشاد|bnar.dilshad@demo.local|ku"
  "ronak_hemin|ڕۆناک|هێمن|ronak.hemin@demo.local|ku"
  "kawa_pshtiwan|کاوە|پشتیوان|kawa.pshtiwan@demo.local|ku"
  "lava_serdar|لاڤا|سەردار|lava.serdar@demo.local|ku"
  "azhin_zanyar|ئاژین|زانیار|azhin.zanyar@demo.local|ku"
  "soran_dilan|سۆران|دیلان|soran.dilan@demo.local|ku"
  "nawal_chiman|نەوال|چیمەن|nawal.chiman@demo.local|ku"
  "hawkar_rojhat|هاوکار|ڕۆژهات|hawkar.rojhat@demo.local|ku"
  "shilan_hiwa|شیلان|هیوا|shilan.hiwa@demo.local|ku"
)

# ═════════════════════════════════════════════════════════════════════════════
#  POSTS — text pool (per language; each user rotates through 8 topics)
# ═════════════════════════════════════════════════════════════════════════════
POSTS_AR=(
  "اليوم بدأت قراءة \"الموافقات\" للشاطبي — كتاب لا يُقرأ مرة واحدة، بل تعاد قراءته كل سنة. #مقاصد #أصول_الفقه"
  "محاضرة رائعة عن علم الحديث وضوابط الجرح والتعديل في جامع الأزهر. #حديث #علوم_شرعية"
  "نقاش علمي مفيد حول مسائل المعاملات المالية المعاصرة. ما رأيكم في حكم العملات الرقمية؟ #فقه #اقتصاد_إسلامي"
  "”إنما الأعمال بالنيات“ — أصل عظيم نختم به اليوم. #حديث #تزكية"
  "نُشر بحثي الجديد في المجلة العلمية حول مقاصد الشريعة في النوازل المعاصرة. #بحث #مقاصد"
  "زيارة مكتبة الإسكندرية للاطلاع على المخطوطات النادرة — تجربة ثرية لكل باحث. #مخطوطات #تراث"
  "السيرة النبوية ليست قصة تُحكى، بل منهج حياة. أنصح بكتب الشيخ المباركفوري. #سيرة"
  "هل التراث الإسلامي مغلق على فهم القدماء أم يحتمل الاجتهاد المعاصر؟ #اجتهاد #فكر_إسلامي"
)

POSTS_KU=(
  "ئەمڕۆ دەستم کرد بە خوێندنەوەی ”الموافقات“ی شاتیبی — کتێبێک کە یەک جار ناخوێنرێتەوە. #مەقاسید #ئوسوول"
  "وانەیەکی ناوازە لەسەر زانستی حەدیس و قواعدی جەرح و تەعدیل. #حەدیس #زانستی_شەرعی"
  "گفتوگۆیەکی زانستی لەسەر کێشە دارایییە هاوچەرخەکان. بۆچی دەربارەی دراوی دیجیتاڵ چییە؟ #فیقه #ئابووری"
  "”تەنها کردارەکان بە نیەتەکانن“ — بنەمایەکی گەورە. #حەدیس #تەزکیە"
  "توێژینەوەی نوێم لە گۆڤاری زانستیدا بڵاوبووەوە سەبارەت بە مەقاسیدی شەریعە لە نوازلدا. #توێژینەوە #مەقاسید"
  "سەردانی کتێبخانەی ئەسکەندەریە بۆ بینینی دەستنووسە دەگمەنەکان. #دەستنووس #میراث"
  "سیرەی پێغەمبەر چیرۆکێک نییە کە بگوترێتەوە، بەڵکو ڕێبازی ژیانە. #سیرە"
  "ئایا میراثی ئیسلامی تەنها بۆ تێگەیشتنی پێشینان دانراوە یان ئیجتیهادی هاوچەرخی هەیە؟ #ئیجتیهاد"
)

# ═════════════════════════════════════════════════════════════════════════════
#  STORY captions
# ═════════════════════════════════════════════════════════════════════════════
STORY_AR=(
  "صباح الخير — يوم بحث جديد في الفقه المقارن 📚"
  "من مكتبتي اليوم: ”إعلام الموقعين“ لابن القيم"
  "نقاش هادئ مع طلاب الدراسات العليا"
  "كأس قهوة قبل المحاضرة ☕"
)
STORY_KU=(
  "بەیانیتان باش — ڕۆژێکی توێژینەوەی نوێ لە فیقهی بەراوردکاری 📚"
  "لە کتێبخانەکەم ئەمڕۆ: ”إعلام الموقعین“ی ئیبن قەیم"
  "گفتوگۆیەکی ئارام لەگەڵ قوتابیانی ماستەر"
  "کاسە قاوەیەک پێش وانە ☕"
)

# ═════════════════════════════════════════════════════════════════════════════
#  RESEARCH — title / abstract / description / tags
# ═════════════════════════════════════════════════════════════════════════════
RES_TITLE_AR=(
  "مقاصد الشريعة الإسلامية وأثرها في الفقه المعاصر"
  "ضوابط التعامل مع الأحاديث المتعارضة عند المحدثين"
  "النوازل الفقهية في المعاملات المالية الإلكترونية"
)
RES_TITLE_KU=(
  "مەقاسیدی شەریعەی ئیسلامی و کاریگەرییەکانی لە فیقهی هاوچەرخدا"
  "ڕێسای مامەڵە لەگەڵ حەدیسە بەرگوەکڕاوەکان لە لای محەدیسەکان"
  "نوازلی فیقهی لە مامەڵە دارایییە ئەلیکترۆنییەکاندا"
)

RES_DESC_AR=(
  "تتناول هذه الدراسة المقاصد الكلية للشريعة الإسلامية بدءاً من تأصيل الشاطبي في الموافقات، مروراً بتطورها عند العلماء المعاصرين كابن عاشور والريسوني، وانتهاءً بتطبيقاتها في النوازل الفقهية الحديثة من معاملات مصرفية وقضايا طبية حيوية."
  "تبحث هذه الدراسة في مناهج المحدثين في التعامل مع الأحاديث المتعارضة ظاهراً، عبر مدارس الجمع والترجيح والنسخ والتوقف، مع تطبيق عملي على نماذج من الصحيحين."
  "تستعرض الدراسة النوازل الفقهية المعاصرة في المعاملات المالية الإلكترونية: العملات الرقمية، المحافظ الذكية، والتمويل الجماعي، مع تأصيل شرعي وتطبيق على القرارات الفقهية الحديثة."
)
RES_DESC_KU=(
  "ئەم لێکۆڵینەوەیە مەقاسیدی گشتی شەریعەی ئیسلامی لێکدەداتەوە، لە بنەماکانی شاتیبی لە الموافقاتەوە، تێپەڕ بە گەشەسەندنی لای زانایانی هاوچەرخ وەک ئیبن عاشور و ڕەیسوونی، تا گەیشتن بە بەکارهێنانی لە نوازلی فیقهی نوێدا."
  "ئەم توێژینەوەیە سەرنج دەخاتە سەر میتۆدی محەدیسەکان لە مامەڵەکردن لەگەڵ حەدیسە بەرگوەکڕاوەکان، لە ڕێگەی قوتابخانەکانی جەمع، تەرجیح، نەسخ و تەواقف."
  "ئەم لێکۆڵینەوەیە نوازلی فیقهی هاوچەرخ لە مامەڵە دارایی ئەلیکترۆنییەکاندا دەخوێنێتەوە: دراوە دیجیتاڵییەکان، جزدانی زیرەک و تەمویلی گشتی، لەگەڵ ڕیشەی شەرعی."
)

RES_ABS_AR=(
  "ملخص: نقدم رؤية أصولية شاملة لمقاصد الشريعة وتطبيقها على النوازل المعاصرة، مع نماذج تطبيقية مدعومة بمراجع علمية معتمدة."
  "ملخص: نتناول مناهج العلماء في الجمع بين الأحاديث المتعارضة مع تطبيقات من البخاري ومسلم."
  "ملخص: نعرض دراسة موسعة لحكم المعاملات الإلكترونية الحديثة من منظور أصولي معاصر."
)
RES_ABS_KU=(
  "پوختە: لێرەدا تێڕوانینێکی ئوسوولی پێشکەش دەکەین بۆ مەقاسید و چۆن لە نوازلی هاوچەرخدا بەکار بهێنرێن."
  "پوختە: میتۆدی زانایان دەخوێنینەوە بۆ کۆکردنەوەی حەدیسە بەرگوەکڕاوەکان لەگەڵ نموونەی پراکتیکی."
  "پوختە: لێکۆڵینەوەیەکی فراوان پێشکەش دەکەین بۆ حوکمی مامەڵە ئەلیکترۆنییە نوێیەکان."
)

RES_KW_AR=(
  "مقاصد الشريعة, الفقه المعاصر, النوازل, الاقتصاد الإسلامي, الشاطبي"
  "علم الحديث, الأحاديث المتعارضة, الجمع والترجيح, البخاري, مسلم"
  "المعاملات المالية, العملات الرقمية, الفقه الإلكتروني, البلوكتشين"
)
RES_KW_KU=(
  "مەقاسیدی شەریعە, فیقهی هاوچەرخ, نوازل, ئابووری ئیسلامی, شاتیبی"
  "زانستی حەدیس, حەدیسە بەرگوەکڕاو, جەمع و تەرجیح, بوخاری, مسلم"
  "مامەڵە دارایی, دراوە دیجیتاڵ, فیقهی ئەلیکترۆنی, بلۆکچەین"
)
RES_TAGS_AR='["مقاصد","فقه","أصول","arabic","islamic-studies"]'
RES_TAGS_KU='["مەقاسید","فیقه","ئوسوول","kurdish","islamic-studies"]'

# ═════════════════════════════════════════════════════════════════════════════
#  Q&A — title / body / answers
# ═════════════════════════════════════════════════════════════════════════════
Q_TITLE_AR=(
  "كيف نوفّق بين القياس الأصولي والاجتهاد المعاصر في النوازل المالية؟"
  "ما الفرق العملي بين السنة التقريرية والسنة الفعلية في الاستدلال؟"
  "هل المنهج المقاصدي بديل عن الفقه التقليدي أم متمم له؟"
  "ما حكم العملات الرقمية في الفقه المعاصر؟"
  "كيف نقرأ السيرة في ضوء التحديات الأكاديمية الحديثة؟"
  "أنصحوني بقائمة كتب لبدء دراسة علوم القرآن"
)
Q_BODY_AR=(
  "أبحث عن مراجع تجمع بين الأصول الكلاسيكية وتطبيقاتها المعاصرة."
  "أحتاج إلى أمثلة من كتب الحديث المعتمدة مع التفريق بين أنواعها."
  "قرأت آراء متباينة — أريد قائمة مراجع أكاديمية موثوقة."
  "بدأت قراءة بحث للمجمع الفقهي، وأريد بقية الآراء العلمية."
  "كيف نطبق المنهج التاريخي النقدي مع احترام الأصول الشرعية؟"
  "أنصحوني بمسار دراسي للمبتدئين، خصوصاً في القراءات والتفسير."
)
Q_TITLE_KU=(
  "چۆن قیاسی ئوسوولی و ئیجتیهادی هاوچەرخ کۆدەکەینەوە لە نوازلی دارایی؟"
  "جیاوازی پراکتیکی نێوان سوننەی تەقریری و سوننەی فیعلی لە دەلیلی فیقهیدا چییە؟"
  "ئایا میتۆدی مەقاسیدی جێگرەوەی فیقهی نەریتییە یان تەواوکارییەتی؟"
  "حوکمی دراوە دیجیتاڵییەکان لە فیقهی هاوچەرخدا چییە؟"
  "چۆن سیرە بخوێنینەوە لە ڕووناکی ڕەخنەی ئەکادیمی نوێدا؟"
  "تکایە لیستی کتێب پێشنیار بکەن بۆ دەستپێکی خوێندنی عوولوومی قورئان"
)
Q_BODY_KU=(
  "بەدوای سەرچاوەدا دەگەڕێم کە ئوسوولی کلاسیکی و بەکارهێنانی هاوچەرخ کۆبکاتەوە."
  "نموونەی پراکتیکی لە کتێبی پەسەندکراوی حەدیس پێویستە."
  "لە زانکۆدا بیروڕای جیاوازم خوێندووە — تکایە سەرچاوەی ئەکادیمی پێبڵێن."
  "دەستم کرد بە خوێندنەوەی توێژینەوەیەک، بۆچوونی ئەکادیمیتان پێویستە."
  "چۆن میتۆدی مێژوویی ڕەخنەیی بەکار دەهێنین لەگەڵ ڕێزگرتن لە سەرچاوەکان؟"
  "تکایە ڕێگەی خوێندن بۆ سەرەتاکان پێشنیار بکەن، بەتایبەتی لە قیراءات."
)

ANS_AR=(
  "أرى أن المرجع الأصلي هو ”الموافقات“ للشاطبي مع ”نظرية المقاصد“ لابن عاشور."
  "في رأيي، لا تعارض بين الأصول والاجتهاد المعاصر إذا فُهمت المقاصد فهماً صحيحاً."
  "أنصح بمراجعة ”تيسير علم أصول الفقه“ للجديع — منهجي وأكاديمي."
  "هذه المسألة تحتاج إلى تأصيل قبل التطبيق — ابدأ بـ ”الإحكام“ للآمدي."
  "”مقاصد الشريعة“ للريسوني مرجع تطبيقي معاصر ممتاز."
  "أضف إلى قائمتك ”المناهج الأصولية“ للدريني — مفيد للمقارنات الحديثة."
)
ANS_KU=(
  "بڕوام وایە سەرچاوەی بنەڕەتی ”الموافقات“ی شاتیبییە لەگەڵ ”نظریة المقاصد“ی ئیبن عاشور."
  "بە بۆچوونی من، هیچ بەرگوەکڕانێک نییە لە نێوان ئوسوول و ئیجتیهادی هاوچەرخدا."
  "پێشنیار دەکەم ”تیسیر علم أصول الفقه“ی جودیع بخوێنرێتەوە."
  "ئەم پرسیارە پێش بەکارهێنان پێویستی بە بنیاتنانی ئوسوولییە — لە ”الإحكام“ی ئامیدییەوە دەستپێبکە."
  "”مقاصد الشريعة“ی ڕەیسوونی سەرچاوەیەکی هاوچەرخ و باشە."
  "لە لیستەکەت ”المناهج الأصولية“ی دەرینی زیاد بکە — مفیدە."
)

# ═════════════════════════════════════════════════════════════════════════════
#  COMMENTS
# ═════════════════════════════════════════════════════════════════════════════
COMMENT_AR=(
  "بحث قيم بارك الله فيك، أضفت ملاحظة في تعليقي."
  "ما شاء الله — أقترح إضافة مرجع للإمام الشاطبي."
  "أرى أن النقطة الثالثة تحتاج إلى تفصيل أكبر."
  "أحسنت — هذا يحتاج إلى مقال مستقل."
  "أتفق معك في الجوهر، أختلف في التطبيق."
)
COMMENT_KU=(
  "توێژینەوەیەکی بەنرخە، خوا لێت ڕازی بێت — تێبینییەکم زیادکرد."
  "ماشاءاللە — پێشنیار دەکەم سەرچاوەی ئیمام شاتیبی زیاد بکرێت."
  "بڕوام وایە خاڵی سێیەم پێویستی بە ووردبینی زیاترە."
  "ئەفەرین — ئەمە پێویستی بە بابەتێکی سەربەخۆیە."
  "لە بنەڕەتدا هاوڕام، لە بەکارهێناندا جیاوازم."
)

REPLY_AR=(
  "أحسنت، أوافقك تماماً."
  "ملاحظة قيمة، شكراً لك."
  "أرى أن نضيف نقطة مهمة."
)
REPLY_KU=(
  "ئەفەرین، تەواو هاوڕام لەگەڵت."
  "تێبینییەکی بەنرخە، سوپاس."
  "بڕوام وایە خاڵێکی گرنگ زیاد بکرێت."
)

# ═════════════════════════════════════════════════════════════════════════════
#  SOUNDS — pretend audio entries (no actual file, just metadata URLs)
# ═════════════════════════════════════════════════════════════════════════════
SOUND_TITLES_AR=(
  "تلاوة من سورة البقرة — الشيخ المنشاوي"
  "أنشودة طلب العلم"
  "محاضرة قصيرة عن النية"
)
SOUND_TITLES_KU=(
  "تەلاوەتێک لە سورەتی بەقەرە"
  "گۆرانی خوێندنی زانست"
  "وانەیەکی کورت لەسەر نیەت"
)

# ═════════════════════════════════════════════════════════════════════════════
#  STATE
# ═════════════════════════════════════════════════════════════════════════════
declare -a TOKENS USERIDS USERNAMES FNAMES LNAMES EMAILS LANGS
declare -a POST_COUNT POST_IDS POST_OWNERS
declare -a STORY_IDS STORY_OWNERS
declare -a RES_IDS RES_OWNERS
declare -a Q_IDS Q_OWNERS Q_ANSWERS
declare -a SOUND_IDS

# ═════════════════════════════════════════════════════════════════════════════
#  Helpers
# ═════════════════════════════════════════════════════════════════════════════

# Real image URL — picsum returns a deterministic JPEG by seed.
img_url() {
  local seed=$1 w=${2:-800} h=${3:-600}
  printf 'https://picsum.photos/seed/%s/%d/%d' "$seed" "$w" "$h"
}

# Pretend audio URL — server doesn't fetch, just stores the string.
audio_url() {
  local seed=$1
  printf 'https://cdn.demo.local/audio/%s.mp3' "$seed"
}

USER_COUNT=${#USERS[@]}

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 1 — register / login
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 1 — authenticate ${USER_COUNT} users (password=${PASSWORD})"
for entry in "${USERS[@]}"; do
  IFS='|' read -r username fname lname email lang <<<"$entry"

  body=$(jq -nc \
    --arg u "$username" --arg f "$fname" --arg l "$lname" \
    --arg e "$email"   --arg p "$PASSWORD" \
    '{username:$u, fname:$f, lname:$l, email:$e, password:$p}')

  resp=$(api POST /api/v1/auth/register "$body")
  token=$(jq -r '.accessToken // empty' <<<"$resp")
  uid=$(jq -r '.user.id // empty' <<<"$resp")

  if [[ -z "$token" ]]; then
    login=$(jq -nc --arg u "$username" --arg p "$PASSWORD" \
      '{username:$u, password:$p}')
    resp=$(api POST /api/v1/auth/login "$login")
    token=$(jq -r '.accessToken // empty' <<<"$resp")
    uid=$(jq -r '.user.id // empty' <<<"$resp")
  fi

  if [[ -z "$token" ]]; then
    fail "$username — auth failed: $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    TOKENS+=(""); USERIDS+=("")
  else
    TOKENS+=("$token"); USERIDS+=("$uid")
    ok "$username  $fname $lname  (id=${uid:0:8}…)"
  fi
  USERNAMES+=("$username"); FNAMES+=("$fname"); LNAMES+=("$lname")
  EMAILS+=("$email"); LANGS+=("$lang")
  POST_COUNT+=(0)
done

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 2 — profile bio / location / academic title
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 2 — enriching profiles (bio / location / institution)"

PROFILE_BIO_AR=(
  "باحث في الدراسات الإسلامية — جامعة الأزهر"
  "أستاذ في كلية الشريعة، تخصص أصول الفقه"
  "طالب دراسات عليا في علوم القرآن"
  "محاضر في جامعة الإمام محمد بن سعود"
  "مهتمة بالفقه المقارن والمعاملات المعاصرة"
)
PROFILE_BIO_KU=(
  "توێژەری لێکۆڵینەوەی ئیسلامی — تایبەتمەند لە فیقه و ئوسوول"
  "مامۆستای کۆلێژی شەریعە لە زانکۆی سلێمانی"
  "قوتابی ماستەر لە عوولوومی قورئان"
  "مامۆستای زانکۆی ساڵح اڵدین — هەولێر"
  "تایبەتمەند لە فیقهی بەراوردکاری و مامەڵە هاوچەرخەکان"
)
LOCATION_AR=("القاهرة، مصر" "الرياض، السعودية" "بغداد، العراق" "دمشق، سوريا" "مكة المكرمة")
LOCATION_KU=("سلێمانی، کوردستان" "هەولێر، کوردستان" "دهۆک، کوردستان" "کەرکوک، کوردستان" "ماهاباد، ڕۆژهەڵات")
INST_AR=("جامعة الأزهر" "جامعة الإمام محمد بن سعود" "جامعة بغداد" "جامعة دمشق" "جامعة الكويت")
INST_KU=("زانکۆی سلێمانی" "زانکۆی ساڵح اڵدین" "زانکۆی دهۆک" "زانکۆی کۆیە" "زانکۆی هەڵەبجە")
TITLE_AR=("أستاذ مساعد" "محاضر" "باحث" "طالب دكتوراه" "أستاذ دكتور")
TITLE_KU=("یاریدەدەری پرۆفیسۆر" "مامۆستا" "توێژەر" "قوتابی دکتۆرا" "پرۆفیسۆر")

p_done=0
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  case "${LANGS[$i]}" in
    ar) bio=${PROFILE_BIO_AR[$(( i % ${#PROFILE_BIO_AR[@]} ))]}
        loc=${LOCATION_AR[$(( i % ${#LOCATION_AR[@]} ))]}
        ins=${INST_AR[$(( i % ${#INST_AR[@]} ))]}
        tit=${TITLE_AR[$(( i % ${#TITLE_AR[@]} ))]} ;;
    ku) bio=${PROFILE_BIO_KU[$(( i % ${#PROFILE_BIO_KU[@]} ))]}
        loc=${LOCATION_KU[$(( i % ${#LOCATION_KU[@]} ))]}
        ins=${INST_KU[$(( i % ${#INST_KU[@]} ))]}
        tit=${TITLE_KU[$(( i % ${#TITLE_KU[@]} ))]} ;;
  esac
  # Server enum is Language.{AR,CKB,EN} — map our "ku" → CKB.
  case "${LANGS[$i]}" in
    ar) lang_enum="AR" ;;
    ku) lang_enum="CKB" ;;
    *)  lang_enum="EN" ;;
  esac
  body=$(jq -nc \
    --arg b "$bio" --arg l "$loc" --arg t "$tit" --arg ins "$ins" \
    --arg w "https://demo.local/profile/${USERNAMES[$i]}" \
    --arg cl "$lang_enum" \
    '{profileBio:$b, location:$l, academicTitle:$t, institutionName:$ins, websiteUrl:$w, contentLanguage:$cl}')
  code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X PATCH \
    -H "Authorization: Bearer ${TOKENS[$i]}" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "$BASE_URL/api/v1/users/me/profile")
  [[ "$code" =~ ^20 ]] && p_done=$(( p_done + 1 ))
done
ok "profiles updated: $p_done/$USER_COUNT"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 3 — follow graph (each user follows next 6 peers + close-friends 2)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 3 — follow graph + close-friends"
follow_ok=0; cf_ok=0
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  for off in 1 2 3 4 5 6; do
    j=$(( (i + off) % USER_COUNT ))
    [[ "$j" -eq "$i" || -z "${USERIDS[$j]}" ]] && continue
    code=$(api_code POST "/api/v1/users/${USERIDS[$j]}/follow" "${TOKENS[$i]}")
    [[ "$code" =~ ^20 ]] && follow_ok=$(( follow_ok + 1 ))
  done
  # 2 close-friends (offsets 1 & 2)
  for off in 1 2; do
    j=$(( (i + off) % USER_COUNT ))
    [[ "$j" -eq "$i" || -z "${USERIDS[$j]}" ]] && continue
    code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
      -H "Authorization: Bearer ${TOKENS[$i]}" \
      "$BASE_URL/api/v1/users/me/close-friends/${USERIDS[$j]}")
    [[ "$code" =~ ^20 ]] && cf_ok=$(( cf_ok + 1 ))
  done
done
ok "$follow_ok follow edges, $cf_ok close-friend bindings"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 4 — posts (6 per user — 3 TEXT, 3 IMAGE with real picsum URLs)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 4 — posts (6 per user: 3 text + 3 image)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  base=$(( (i * 2) % 8 ))
  for k in 0 1 2 3 4 5; do
    idx=$(( (base + k) % 8 ))
    case "${LANGS[$i]}" in
      ar) txt=${POSTS_AR[$idx]} ;;
      ku) txt=${POSTS_KU[$idx]} ;;
    esac

    if (( k < 3 )); then
      # TEXT post
      body=$(jq -nc --arg t "$txt" \
        '{postType:"POST", visibility:"PUBLIC", textContent:$t}')
    else
      # IMAGE post — single real picsum URL
      seed="${USERNAMES[$i]}-${k}"
      url=$(img_url "$seed" 1080 1080)
      body=$(jq -nc --arg t "$txt" --arg u "$url" \
        '{postType:"POST", visibility:"PUBLIC", textContent:$t,
          mediaUrls:[$u], mediaTypes:["IMAGE"]}')
    fi
    resp=$(api POST /api/v1/posts "$body" "${TOKENS[$i]}")
    pid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$pid" ]]; then
      POST_IDS+=("$pid")
      POST_OWNERS+=("$i")
      POST_COUNT[$i]=$(( POST_COUNT[$i] + 1 ))
    else
      warn "post create failed (${USERNAMES[$i]}): $(jq -c '.message // .error // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "${#POST_IDS[@]} posts created"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 5 — stories (2 per user — PHOTO + one with a poll)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 5 — stories (2 per user) + polls on every 3rd story"
poll_ok=0
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  for k in 0 1; do
    case "${LANGS[$i]}" in
      ar) cap=${STORY_AR[$(( (i + k) % ${#STORY_AR[@]} ))]} ;;
      ku) cap=${STORY_KU[$(( (i + k) % ${#STORY_KU[@]} ))]} ;;
    esac
    seed="story-${USERNAMES[$i]}-${k}"
    media=$(img_url "$seed" 1080 1920)
    thumb=$(img_url "$seed" 270 480)
    body=$(jq -nc --arg c "$cap" --arg m "$media" --arg th "$thumb" \
      '{storyType:"PHOTO", visibility:"PUBLIC",
        mediaUrl:$m, thumbnailUrl:$th, textContent:$c, lifetimeHours:24}')
    resp=$(api POST /api/v1/stories "$body" "${TOKENS[$i]}")
    sid=$(jq -r '.storyId // .id // empty' <<<"$resp")
    if [[ -n "$sid" ]]; then
      STORY_IDS+=("$sid")
      STORY_OWNERS+=("$i")
    fi
  done
done
ok "${#STORY_IDS[@]} stories created"

# Poll on every 3rd story
for k in "${!STORY_IDS[@]}"; do
  (( k % 3 == 0 )) || continue
  owner=${STORY_OWNERS[$k]}
  sid=${STORY_IDS[$k]}
  case "${LANGS[$owner]}" in
    ar) q="أيهما أهم: التأصيل أم التطبيق؟"; a="التأصيل"; b="التطبيق" ;;
    ku) q="کامیان گرنگترە: بنیاتنان یان بەکارهێنان؟"; a="بنیاتنان"; b="بەکارهێنان" ;;
  esac
  body=$(jq -nc --arg q "$q" --arg a "$a" --arg b "$b" \
    '{question:$q, optionA:$a, optionB:$b}')
  resp=$(api POST "/api/v1/stories/$sid/poll" "$body" "${TOKENS[$owner]}")
  pid=$(jq -r '.pollId // .id // empty' <<<"$resp")
  if [[ -n "$pid" ]]; then
    poll_ok=$(( poll_ok + 1 ))
    # 3 voters
    for off in 1 2 3; do
      v=$(( (owner + off) % USER_COUNT ))
      [[ "$v" -eq "$owner" || -z "${TOKENS[$v]}" ]] && continue
      choice="A"; (( v % 2 == 0 )) && choice="B"
      api_code POST "/api/v1/polls/$pid/vote?choice=$choice" "${TOKENS[$v]}" >/dev/null
    done
  fi
done
ok "$poll_ok story polls created with votes"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 6 — researches (2 per user — multipart with real cover image)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 6 — researches (2 per user, published)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  for k in 0 1; do
    case "${LANGS[$i]}" in
      ar) title=${RES_TITLE_AR[$(( (i + k) % ${#RES_TITLE_AR[@]} ))]}
          desc=${RES_DESC_AR[$(( (i + k) % ${#RES_DESC_AR[@]} ))]}
          abs=${RES_ABS_AR[$(( (i + k) % ${#RES_ABS_AR[@]} ))]}
          kw=${RES_KW_AR[$(( (i + k) % ${#RES_KW_AR[@]} ))]}
          tags=$RES_TAGS_AR ;;
      ku) title=${RES_TITLE_KU[$(( (i + k) % ${#RES_TITLE_KU[@]} ))]}
          desc=${RES_DESC_KU[$(( (i + k) % ${#RES_DESC_KU[@]} ))]}
          abs=${RES_ABS_KU[$(( (i + k) % ${#RES_ABS_KU[@]} ))]}
          kw=${RES_KW_KU[$(( (i + k) % ${#RES_KW_KU[@]} ))]}
          tags=$RES_TAGS_KU ;;
    esac
    data=$(jq -nc --arg t "$title" --arg d "$desc" --arg a "$abs" \
                  --arg k "$kw" --argjson tg "$tags" \
      '{title:$t, description:$d, abstractText:$a, keywords:$k,
        visibility:"PUBLIC", commentsEnabled:true, downloadsEnabled:true,
        tags:$tg}')
    resp=$(curl -sS --max-time 60 \
      -H "Authorization: Bearer ${TOKENS[$i]}" \
      -F "data=$data;type=application/json" \
      "$BASE_URL/api/v1/researches")
    rid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$rid" ]]; then
      # publish
      pub=$(api POST "/api/v1/researches/$rid/publish" "" "${TOKENS[$i]}")
      status=$(jq -r '.status // empty' <<<"$pub")
      if [[ "$status" == "PUBLISHED" ]]; then
        RES_IDS+=("$rid")
        RES_OWNERS+=("$i")
      else
        warn "publish failed for ${USERNAMES[$i]}: $(jq -c '.message // .' <<<"$pub" 2>/dev/null)"
      fi
    else
      warn "research create failed (${USERNAMES[$i]}): $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "${#RES_IDS[@]} researches published"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 7 — Q&A (3 questions per user; 2-3 answers each; accept the first)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 7 — Q&A questions + peer answers + accepts"
ans_total=0; accept_ok=0
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  base=$(( (i * 3) % 6 ))
  for k in 0 1 2; do
    idx=$(( (base + k) % 6 ))
    case "${LANGS[$i]}" in
      ar) qt=${Q_TITLE_AR[$idx]}; qb=${Q_BODY_AR[$idx]}
          tags='["fiqh","arabic","scholarship"]' ;;
      ku) qt=${Q_TITLE_KU[$idx]}; qb=${Q_BODY_KU[$idx]}
          tags='["fiqh","kurdish","scholarship"]' ;;
    esac
    body=$(jq -nc --arg t "$qt" --arg b "$qb" --argjson tg "$tags" \
      '{title:$t, body:$b, tags:$tg, answersLocked:false}')
    resp=$(api POST /api/v1/questions "$body" "${TOKENS[$i]}")
    qid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$qid" ]]; then
      Q_IDS+=("$qid")
      Q_OWNERS+=("$i")
    else
      warn "question failed (${USERNAMES[$i]}): $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "${#Q_IDS[@]} questions created"

note "answering each question with 2-3 peers"
for k in "${!Q_IDS[@]}"; do
  qid=${Q_IDS[$k]}
  owner=${Q_OWNERS[$k]}
  n_answers=$(( (k % 3 == 0) ? 3 : 2 ))
  first_aid=""
  for a in $(seq 1 "$n_answers"); do
    responder=$(( (owner + a) % USER_COUNT ))
    [[ "$responder" -eq "$owner" || -z "${TOKENS[$responder]}" ]] && \
      responder=$(( (responder + 1) % USER_COUNT ))
    [[ "$responder" -eq "$owner" || -z "${TOKENS[$responder]}" ]] && continue

    ans_idx=$(( (k + a) % 6 ))
    case "${LANGS[$responder]}" in
      ar) txt=${ANS_AR[$ans_idx]} ;;
      ku) txt=${ANS_KU[$ans_idx]} ;;
    esac
    body=$(jq -nc --arg b "$txt" '{body:$b}')
    resp=$(api POST "/api/v1/questions/$qid/answers" "$body" "${TOKENS[$responder]}")
    aid=$(jq -r '.id // empty' <<<"$resp")
    [[ -n "$aid" ]] && ans_total=$(( ans_total + 1 ))
    [[ -z "$first_aid" && -n "$aid" ]] && first_aid="$aid"
  done
  # Owner accepts the first answer (when present)
  if [[ -n "$first_aid" ]]; then
    code=$(api_code POST "/api/v1/questions/$qid/answers/$first_aid/accept" "${TOKENS[$owner]}")
    [[ "$code" =~ ^20 ]] && accept_ok=$(( accept_ok + 1 ))
  fi
done
ok "$ans_total answers posted • $accept_ok accepted"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 8 — sounds (3 from admin/scholar users to seed the library)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 8 — sound library entries"
sound_ok=0
for i in 0 5 10 15 20; do
  (( i >= USER_COUNT )) && break
  [[ -z "${TOKENS[$i]}" ]] && continue
  case "${LANGS[$i]}" in
    ar) titles=("${SOUND_TITLES_AR[@]}") ;;
    ku) titles=("${SOUND_TITLES_KU[@]}") ;;
  esac
  for k in 0 1 2; do
    t=${titles[$k]}
    aurl=$(audio_url "${USERNAMES[$i]}-sound-${k}")
    cover=$(img_url "sound-${USERNAMES[$i]}-${k}" 600 600)
    case $k in
      0) cat="recitation" ;;
      1) cat="nasheed" ;;
      2) cat="lecture" ;;
    esac
    body=$(jq -nc --arg t "$t" --arg a "$aurl" --arg c "$cover" --arg cat "$cat" \
      '{title:$t, artistName:"Demo", audioUrl:$a, coverArtUrl:$c, durationSeconds:90, category:$cat}')
    resp=$(api POST /api/v1/sounds "$body" "${TOKENS[$i]}")
    sid=$(jq -r '.id // .soundId // empty' <<<"$resp")
    if [[ -n "$sid" ]]; then
      SOUND_IDS+=("$sid")
      sound_ok=$(( sound_ok + 1 ))
    fi
  done
done
ok "$sound_ok sounds registered"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 9 — engagement: reactions, saves, views, comments + replies on posts
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 9 — engagement on posts (reactions, saves, views, comments + replies)"
react_ok=0; save_ok=0; view_ok=0; comm_ok=0; reply_ok=0
for k in "${!POST_IDS[@]}"; do
  pid=${POST_IDS[$k]}
  owner=${POST_OWNERS[$k]}
  # 5 reactors
  for off in 1 2 3 4 5; do
    r=$(( (owner + off) % USER_COUNT ))
    [[ "$r" -eq "$owner" || -z "${TOKENS[$r]}" ]] && continue
    code=$(api_code POST "/api/v1/posts/$pid/reactions" "${TOKENS[$r]}")
    [[ "$code" =~ ^20 ]] && react_ok=$(( react_ok + 1 ))
  done
  # 3 savers
  for off in 1 2 4; do
    s=$(( (owner + off) % USER_COUNT ))
    [[ "$s" -eq "$owner" || -z "${TOKENS[$s]}" ]] && continue
    code=$(api_code POST "/api/v1/posts/$pid/saves" "${TOKENS[$s]}")
    [[ "$code" =~ ^20 ]] && save_ok=$(( save_ok + 1 ))
  done
  # 4 viewers
  for off in 1 2 3 5; do
    v=$(( (owner + off) % USER_COUNT ))
    [[ "$v" -eq "$owner" || -z "${TOKENS[$v]}" ]] && continue
    code=$(api_code POST "/api/v1/posts/$pid/views" "${TOKENS[$v]}")
    [[ "$code" =~ ^20 ]] && view_ok=$(( view_ok + 1 ))
  done
  # 2 commenters; pick reply on the first only
  first_cid=""
  for off in 1 3; do
    c=$(( (owner + off) % USER_COUNT ))
    [[ "$c" -eq "$owner" || -z "${TOKENS[$c]}" ]] && continue
    case "${LANGS[$c]}" in
      ar) ct=${COMMENT_AR[$(( (k + off) % ${#COMMENT_AR[@]} ))]} ;;
      ku) ct=${COMMENT_KU[$(( (k + off) % ${#COMMENT_KU[@]} ))]} ;;
    esac
    body=$(jq -nc --arg t "$ct" '{textContent:$t}')
    resp=$(curl -sS --max-time 15 -X POST \
      -H "Authorization: Bearer ${TOKENS[$c]}" \
      -H "Content-Type: application/json" \
      -d "$body" \
      "$BASE_URL/api/v1/posts/$pid/comments")
    cid=$(jq -r '.id // .commentId // empty' <<<"$resp")
    if [[ -n "$cid" ]]; then
      comm_ok=$(( comm_ok + 1 ))
      [[ -z "$first_cid" ]] && first_cid="$cid"
    fi
  done
  # 1 reply on the first comment
  if [[ -n "$first_cid" ]]; then
    r=$(( (owner + 2) % USER_COUNT ))
    [[ "$r" -eq "$owner" || -z "${TOKENS[$r]}" ]] && r=$(( (r + 1) % USER_COUNT ))
    case "${LANGS[$r]}" in
      ar) rt=${REPLY_AR[$(( k % ${#REPLY_AR[@]} ))]} ;;
      ku) rt=${REPLY_KU[$(( k % ${#REPLY_KU[@]} ))]} ;;
    esac
    body=$(jq -nc --arg t "$rt" '{textContent:$t}')
    code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
      -H "Authorization: Bearer ${TOKENS[$r]}" \
      -H "Content-Type: application/json" \
      -d "$body" \
      "$BASE_URL/api/v1/posts/comments/$first_cid/replies")
    [[ "$code" =~ ^20 ]] && reply_ok=$(( reply_ok + 1 ))
  fi
done
ok "$react_ok reactions • $save_ok saves • $view_ok views • $comm_ok comments • $reply_ok replies"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 10 — engagement on stories (views) + researches (likes/saves)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 10 — engagement on stories + researches"
sview_ok=0
for k in "${!STORY_IDS[@]}"; do
  sid=${STORY_IDS[$k]}
  owner=${STORY_OWNERS[$k]}
  for off in 1 2 3 4; do
    v=$(( (owner + off) % USER_COUNT ))
    [[ "$v" -eq "$owner" || -z "${TOKENS[$v]}" ]] && continue
    code=$(api_code POST "/api/v1/stories/$sid/views" "${TOKENS[$v]}")
    [[ "$code" =~ ^20 ]] && sview_ok=$(( sview_ok + 1 ))
  done
done
ok "$sview_ok story views"

r_react=0; r_save=0; r_view=0
for k in "${!RES_IDS[@]}"; do
  rid=${RES_IDS[$k]}
  owner=${RES_OWNERS[$k]}
  for off in 1 2 3 4 5; do
    u=$(( (owner + off) % USER_COUNT ))
    [[ "$u" -eq "$owner" || -z "${TOKENS[$u]}" ]] && continue
    code=$(api_code POST "/api/v1/researches/$rid/reactions" "${TOKENS[$u]}")
    [[ "$code" =~ ^20 ]] && r_react=$(( r_react + 1 ))
  done
  for off in 1 2 3; do
    u=$(( (owner + off) % USER_COUNT ))
    [[ "$u" -eq "$owner" || -z "${TOKENS[$u]}" ]] && continue
    code=$(api_code POST "/api/v1/researches/$rid/save" "${TOKENS[$u]}")
    [[ "$code" =~ ^20 ]] && r_save=$(( r_save + 1 ))
  done
  for off in 1 2 3 4; do
    u=$(( (owner + off) % USER_COUNT ))
    [[ "$u" -eq "$owner" || -z "${TOKENS[$u]}" ]] && continue
    code=$(api_code POST "/api/v1/researches/$rid/view" "${TOKENS[$u]}")
    [[ "$code" =~ ^20 ]] && r_view=$(( r_view + 1 ))
  done
done
ok "researches: $r_react likes • $r_save saves • $r_view views"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 11 — engagement on Q&A (answer reactions, question saves)
# ═════════════════════════════════════════════════════════════════════════════
step "Stage 11 — engagement on Q&A"
q_save=0
for k in "${!Q_IDS[@]}"; do
  qid=${Q_IDS[$k]}
  owner=${Q_OWNERS[$k]}
  for off in 1 2 3 4; do
    u=$(( (owner + off) % USER_COUNT ))
    [[ "$u" -eq "$owner" || -z "${TOKENS[$u]}" ]] && continue
    code=$(api_code POST "/api/v1/questions/$qid/save" "${TOKENS[$u]}")
    [[ "$code" =~ ^20 ]] && q_save=$(( q_save + 1 ))
  done
done
ok "$q_save question saves"

# ═════════════════════════════════════════════════════════════════════════════
#  SUMMARY
# ═════════════════════════════════════════════════════════════════════════════
echo
printf "%s═══ KURDISH + ARABIC SEED COMPLETE (password=%s) ═══%s\n" \
  "$BOLD" "$PASSWORD" "$RST"
printf "%-3s %-22s %-30s %-32s %-4s\n" \
  "#" "username" "full name" "email" "lang"
printf "%s%s%s\n" "$DIM" "$(printf '─%.0s' {1..95})" "$RST"
for i in "${!USERS[@]}"; do
  printf "%-3s %-22s %-30s %-32s %-4s\n" \
    "$((i+1))" "${USERNAMES[$i]}" "${FNAMES[$i]} ${LNAMES[$i]}" \
    "${EMAILS[$i]}" "${LANGS[$i]}"
done
echo
printf "%sTotals%s  posts=%d  stories=%d  researches=%d  questions=%d  answers=%d  sounds=%d\n" \
  "$BOLD" "$RST" "${#POST_IDS[@]}" "${#STORY_IDS[@]}" "${#RES_IDS[@]}" \
  "${#Q_IDS[@]}" "$ans_total" "$sound_ok"
printf "%sFollow graph%s  %d edges • %d close-friend rows • %d profiles enriched\n" \
  "$BOLD" "$RST" "$follow_ok" "$cf_ok" "$p_done"
printf "%sEngagement%s    post reactions=%d • saves=%d • views=%d • comments=%d • replies=%d\n" \
  "$BOLD" "$RST" "$react_ok" "$save_ok" "$view_ok" "$comm_ok" "$reply_ok"
printf "              story views=%d • polls with votes=%d\n" "$sview_ok" "$poll_ok"
printf "              researches  likes=%d • saves=%d • views=%d\n" "$r_react" "$r_save" "$r_view"
printf "              QnA  accepts=%d • saves=%d\n" "$accept_ok" "$q_save"

echo
echo "Sample login:"
echo "  curl -X POST $BASE_URL/api/v1/auth/login \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"username\":\"ahmed_alazhari\",\"password\":\"$PASSWORD\"}'"

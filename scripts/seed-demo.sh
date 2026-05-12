#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  IRC PLATFORM — DEMO SEEDER
#
#  Creates 15 demo users (Arabic / English / Kurdish names) and, for each:
#    • registers the account, logs them in, captures the JWT
#    • creates 2 posts (one Arabic / Kurdish / English content)
#    • publishes 1 research
#    • opens 1 Q&A question and posts an answer (by another user)
#
#  All accounts share the password "11111111".
#
#  Usage:
#    ./scripts/seed-demo.sh            # uses http://localhost:8080
#    BASE_URL=https://… ./scripts/seed-demo.sh
# ─────────────────────────────────────────────────────────────────────────────

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASSWORD="11111111"

# ── pretty printing ──────────────────────────────────────────────────────────
BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'
YEL=$'\033[33m'; CYN=$'\033[36m'; RST=$'\033[0m'

step()  { printf "%s▶ %s%s\n"  "$CYN" "$*" "$RST"; }
ok()    { printf "  %s✓%s %s\n" "$GRN" "$RST" "$*"; }
warn()  { printf "  %s!%s %s\n" "$YEL" "$RST" "$*"; }
fail()  { printf "  %s✗%s %s\n" "$RED" "$RST" "$*"; }

# ── helpers ──────────────────────────────────────────────────────────────────
api() {
  # api METHOD PATH [BODY] [TOKEN]
  local method=$1 path=$2 body=${3:-} token=${4:-}
  local hdr=(-H "Content-Type: application/json" -H "Accept: application/json")
  [[ -n "$token" ]] && hdr+=(-H "Authorization: Bearer $token")
  if [[ -n "$body" ]]; then
    curl -sS --max-time 30 -X "$method" "${hdr[@]}" -d "$body" "$BASE_URL$path"
  else
    curl -sS --max-time 30 -X "$method" "${hdr[@]}" "$BASE_URL$path"
  fi
}

# ── 15 demo users (mix of Arabic / English / Kurdish) ────────────────────────
#  fields: username | fname | lname | email | bio-language hint
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

# ── per-language demo content ────────────────────────────────────────────────
post_text_ar=(
  "صباح الخير من مجتمع البحث الأكاديمي 🌿 — اليوم بدأت قراءة ورقة جديدة حول الذكاء الاصطناعي في التعليم."
  "نشرت دراستي عن تأثير وسائل التواصل الاجتماعي على الطلاب الجامعيين، مرحبا بنقاشاتكم."
)
post_text_en=(
  "Good morning from the academic research community 🌿 — started reading a new paper on AI in education today."
  "Just shared my findings on the role of social media in higher education. Looking forward to your discussion!"
)
post_text_ku=(
  "بەیانی باش لە کۆمەڵگەی توێژینەوەی ئەکادیمی 🌿 — ئەمڕۆ دەستم کرد بە خوێندنەوەی توێژینەوەیەکی نوێ سەبارەت بە زیرەکی دەستکرد لە پەروەردە."
  "بڵاوکردنەوەی توێژینەوەکەم سەبارەت بە کاریگەری ڕاگەیاندنی کۆمەڵایەتی لەسەر خوێندکارانی زانکۆ — چاوەڕێی گفتوگۆکانتانم."
)

research_title_ar=("الذكاء الاصطناعي في التعليم العالي" "أثر وسائل التواصل الاجتماعي على الطلاب")
research_title_en=("AI in Higher Education" "Impact of Social Media on Students")
research_title_ku=("زیرەکی دەستکرد لە خوێندنی باڵا" "کاریگەری ڕاگەیاندنی کۆمەڵایەتی لەسەر خوێندکاران")

question_ar=(
  "ما هي أفضل طريقة لدمج الذكاء الاصطناعي في المنهج الجامعي؟"
  "كيف نقيس أثر منصات التعلم الإلكتروني على نتائج الطلاب؟"
)
question_en=(
  "What is the best way to integrate AI into the university curriculum?"
  "How do we measure the impact of e-learning platforms on student outcomes?"
)
question_ku=(
  "باشترین ڕێگا چییە بۆ تێکەڵکردنی زیرەکی دەستکرد لە پرۆگرامی زانکۆ؟"
  "چۆن کاریگەری پلاتفۆرمەکانی فێرکاری ئەلیکترۆنی لەسەر ئەنجامی خوێندکاران دەپێوین؟"
)

answer_ar=(
  "أعتقد أن البداية الأنسب هي تدريب أعضاء هيئة التدريس قبل تعديل المقررات."
  "أنصح باستخدام دراسات حالة طويلة الأمد ومقارنة فصول تجريبية مع فصول ضابطة."
)
answer_en=(
  "I think the right starting point is training faculty before redesigning courses."
  "I recommend longitudinal case studies comparing experimental and control cohorts."
)
answer_ku=(
  "بڕوام وایە دەستپێکی گونجاو ڕاهێنانی مامۆستایانە پێش گۆڕینی کۆرسەکان."
  "پێشنیاری توێژینەوەی درێژخایەن دەکەم بۆ بەراوردکردنی پۆلی تاقیکاری و پۆلی کۆنترۆڵ."
)

pick_by_lang() {
  # pick_by_lang <lang> <ar-arr> <en-arr> <ku-arr> <idx>
  local lang=$1 idx=$5
  case "$lang" in
    ar) eval "echo \"\${$2[$idx]}\"" ;;
    en) eval "echo \"\${$3[$idx]}\"" ;;
    ku) eval "echo \"\${$4[$idx]}\"" ;;
  esac
}

json_str() { printf '%s' "$1" | jq -Rs .; }

# ── working state ────────────────────────────────────────────────────────────
declare -a TOKENS USERIDS USERNAMES FNAMES LNAMES EMAILS LANGS
declare -a POST_COUNT RESEARCH_COUNT QNA_COUNT
declare -a QUESTION_IDS QUESTION_OWNERS

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 1 — register + login each user
# ═════════════════════════════════════════════════════════════════════════════
step "Registering 15 users (password = $PASSWORD)"
for entry in "${USERS[@]}"; do
  IFS='|' read -r username fname lname email lang <<<"$entry"

  body=$(jq -nc \
    --arg u "$username" --arg f "$fname" --arg l "$lname" \
    --arg e "$email"   --arg p "$PASSWORD" \
    '{username:$u, fname:$f, lname:$l, email:$e, password:$p}')

  resp=$(api POST /api/v1/auth/register "$body")
  token=$(jq -r '.accessToken // empty' <<<"$resp")

  if [[ -z "$token" ]]; then
    # try login (user may already exist from a previous run)
    login=$(jq -nc --arg u "$username" --arg p "$PASSWORD" \
      '{username:$u, password:$p}')
    resp=$(api POST /api/v1/auth/login "$login")
    token=$(jq -r '.accessToken // empty' <<<"$resp")
  fi

  if [[ -z "$token" ]]; then
    fail "$username — could not get token: $(jq -c '.message // .error // .' <<<"$resp" 2>/dev/null || echo "$resp")"
    TOKENS+=(""); USERIDS+=("")
  else
    uid=$(jq -r '.user.id // empty' <<<"$resp")
    TOKENS+=("$token"); USERIDS+=("$uid")
    ok "$username ($lname, $fname) — id=${uid:0:8}…"
  fi
  USERNAMES+=("$username"); FNAMES+=("$fname"); LNAMES+=("$lname")
  EMAILS+=("$email"); LANGS+=("$lang")
  POST_COUNT+=(0); RESEARCH_COUNT+=(0); QNA_COUNT+=(0)
done

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 2 — patch profile bio (so the user has details)
# ═════════════════════════════════════════════════════════════════════════════
step "Updating profile bios with Arabic / English / Kurdish details"
bio_ar="باحث في الذكاء الاصطناعي والتعليم — جامعة بغداد"
bio_en="AI & Education researcher — University of Oxford"
bio_ku="توێژەری زیرەکی دەستکرد و پەروەردە — زانکۆی سلێمانی"

for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  case "${LANGS[$i]}" in
    ar) bio=$bio_ar; loc="بغداد، العراق" ;;
    en) bio=$bio_en; loc="Oxford, UK"    ;;
    ku) bio=$bio_ku; loc="سلێمانی، کوردستان" ;;
  esac
  body=$(jq -nc --arg b "$bio" --arg l "$loc" \
    '{bio:$b, location:$l}')
  api PATCH /api/v1/users/me "$body" "${TOKENS[$i]}" >/dev/null
done
ok "bios updated"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 3 — each user creates 2 posts
# ═════════════════════════════════════════════════════════════════════════════
step "Creating 2 posts per user (30 total)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  for j in 0 1; do
    case "${LANGS[$i]}" in
      ar) txt=${post_text_ar[$j]} ;;
      en) txt=${post_text_en[$j]} ;;
      ku) txt=${post_text_ku[$j]} ;;
    esac
    body=$(jq -nc --arg t "$txt" \
      '{postType:"TEXT", visibility:"PUBLIC", textContent:$t}')
    resp=$(api POST /api/v1/posts "$body" "${TOKENS[$i]}")
    pid=$(jq -r '.id // empty' <<<"$resp")
    if [[ -n "$pid" ]]; then
      POST_COUNT[$i]=$(( POST_COUNT[$i] + 1 ))
    else
      warn "post failed for ${USERNAMES[$i]}: $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
    fi
  done
done
ok "posts created"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 4 — each user publishes 1 research
# ═════════════════════════════════════════════════════════════════════════════
step "Publishing 1 research per user (15 total)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  case "${LANGS[$i]}" in
    ar) title=${research_title_ar[$((i % 2))]}
        desc="هذه دراسة تجريبية حول الموضوع — تشمل المنهجية والنتائج والمناقشة."
        abs="ملخص: نناقش في هذه الدراسة منهجية متكاملة لتقييم أثر التقنية على التعليم."
        tags='["ai","education","ar"]'
        keywords="الذكاء الاصطناعي, التعليم, التعلم الإلكتروني"
        ;;
    en) title=${research_title_en[$((i % 2))]}
        desc="An empirical study exploring methodology, results and discussion."
        abs="Abstract: this paper proposes an integrated methodology for evaluating the impact of technology on education."
        tags='["ai","education","en"]'
        keywords="artificial intelligence, education, e-learning"
        ;;
    ku) title=${research_title_ku[$((i % 2))]}
        desc="ئەم توێژینەوەیە لێکۆڵینەوەیەکی تاقیکاری ئەکادیمییە کە میتۆد، ئەنجام و گفتوگۆ لەخۆدەگرێت."
        abs="پوختە: ئەم نووسراوەیە میتۆدێکی یەکگرتوو پێشنیار دەکات بۆ هەڵسەنگاندنی کاریگەری تەکنەلۆژیا لەسەر پەروەردە."
        tags='["ai","education","ku"]'
        keywords="زیرەکی دەستکرد، پەروەردە، فێرکاری ئەلیکترۆنی"
        ;;
  esac

  data=$(jq -nc --arg t "$title" --arg d "$desc" --arg a "$abs" \
                --arg k "$keywords" --argjson tg "$tags" \
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
      RESEARCH_COUNT[$i]=$(( RESEARCH_COUNT[$i] + 1 ))
    else
      warn "publish failed for ${USERNAMES[$i]}: $(jq -c '.' <<<"$pub")"
    fi
  else
    warn "research create failed for ${USERNAMES[$i]}: $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
  fi
done
ok "researches published"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 5 — each user asks 1 question
# ═════════════════════════════════════════════════════════════════════════════
step "Opening 1 Q&A question per user (15 total)"
for i in "${!USERS[@]}"; do
  [[ -z "${TOKENS[$i]}" ]] && continue
  case "${LANGS[$i]}" in
    ar) qt=${question_ar[$((i % 2))]}
        qb="نرجو من المجتمع الأكاديمي مشاركة تجاربكم وأبحاثكم ذات الصلة." ;;
    en) qt=${question_en[$((i % 2))]}
        qb="Looking for the academic community to share related experience and references." ;;
    ku) qt=${question_ku[$((i % 2))]}
        qb="داوا دەکەم کۆمەڵگەی ئەکادیمی ئەزموون و سەرچاوەکانیان لەگەڵم بەشدار بکەن." ;;
  esac
  body=$(jq -nc --arg t "$qt" --arg b "$qb" \
    '{title:$t, body:$b, answersLocked:false}')
  resp=$(api POST /api/v1/questions "$body" "${TOKENS[$i]}")
  qid=$(jq -r '.id // empty' <<<"$resp")
  if [[ -n "$qid" ]]; then
    QUESTION_IDS+=("$qid"); QUESTION_OWNERS+=("$i")
    QNA_COUNT[$i]=$(( QNA_COUNT[$i] + 1 ))
  else
    warn "question failed for ${USERNAMES[$i]}: $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
  fi
done
ok "questions opened"

# ═════════════════════════════════════════════════════════════════════════════
#  STAGE 6 — each question gets an answer from a DIFFERENT user
# ═════════════════════════════════════════════════════════════════════════════
step "Posting one answer per question (from another user)"
for k in "${!QUESTION_IDS[@]}"; do
  qid=${QUESTION_IDS[$k]}
  owner=${QUESTION_OWNERS[$k]}
  responder=$(( (owner + 1) % ${#USERS[@]} ))
  [[ -z "${TOKENS[$responder]}" ]] && responder=$(( (responder + 1) % ${#USERS[@]} ))

  case "${LANGS[$responder]}" in
    ar) txt=${answer_ar[$((k % 2))]} ;;
    en) txt=${answer_en[$((k % 2))]} ;;
    ku) txt=${answer_ku[$((k % 2))]} ;;
  esac
  body=$(jq -nc --arg b "$txt" '{body:$b}')
  resp=$(api POST "/api/v1/questions/$qid/answers" "$body" "${TOKENS[$responder]}")
  aid=$(jq -r '.id // empty' <<<"$resp")
  if [[ -z "$aid" ]]; then
    warn "answer failed by ${USERNAMES[$responder]} on q ${qid:0:8}…: $(jq -c '.message // .' <<<"$resp" 2>/dev/null)"
  fi
done
ok "answers posted"

# ═════════════════════════════════════════════════════════════════════════════
#  SUMMARY
# ═════════════════════════════════════════════════════════════════════════════
echo
printf "%s═══ DEMO USERS (password = %s) ═══════════════════════════════════════%s\n" "$BOLD" "$PASSWORD" "$RST"
printf "%-3s %-18s %-22s %-32s %-4s %-5s %-5s %-4s\n" \
  "#" "username" "full name" "email" "lang" "posts" "rsrch" "qna"
printf "%s%s%s\n" "$DIM" "$(printf '─%.0s' {1..100})" "$RST"
for i in "${!USERS[@]}"; do
  printf "%-3s %-18s %-22s %-32s %-4s %-5s %-5s %-4s\n" \
    "$((i+1))" \
    "${USERNAMES[$i]}" \
    "${FNAMES[$i]} ${LNAMES[$i]}" \
    "${EMAILS[$i]}" \
    "${LANGS[$i]}" \
    "${POST_COUNT[$i]}" \
    "${RESEARCH_COUNT[$i]}" \
    "${QNA_COUNT[$i]}"
done
echo

# Aggregate totals
total_posts=0; total_research=0; total_qna=0
for n in "${POST_COUNT[@]}";     do total_posts=$(( total_posts + n )); done
for n in "${RESEARCH_COUNT[@]}"; do total_research=$(( total_research + n )); done
for n in "${QNA_COUNT[@]}";      do total_qna=$(( total_qna + n )); done

printf "%sTotals:%s %s posts • %s researches • %s questions • %s answers (from peers)\n" \
  "$BOLD" "$RST" "$total_posts" "$total_research" "$total_qna" "${#QUESTION_IDS[@]}"

echo
echo "Login example:"
echo "  curl -X POST $BASE_URL/api/v1/auth/login \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"username\":\"ahmed_ali\",\"password\":\"$PASSWORD\"}'"

#!/usr/bin/env bash
# =============================================================================
#  IRC API — Full Integration Test Script
# =============================================================================

set -uo pipefail

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

step() { echo -e "\n${CYAN}${BOLD}━━━ $1 ━━━${NC}"; }
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
fail() { echo -e "${RED}✗ $1${NC}"; }
info() { echo -e "  $1"; }

# ── Prerequisite check ────────────────────────────────────────────────────────
if ! command -v jq >/dev/null 2>&1; then
  fail "jq is required. Install: brew install jq"; exit 1
fi

# =============================================================================
#  INTERACTIVE SETUP
# =============================================================================
echo -e "\n${BOLD}IRC API Integration Test — Setup${NC}\n"

read -r -p "$(echo -e "${CYAN}Base URL${NC} [http://localhost:8080]: ")" INPUT_URL
BASE_URL="${INPUT_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"

echo ""
echo -e "${BOLD}File Uploads${NC} (press Enter to skip)\n"

read -r -p "$(echo -e "${CYAN}Research paper${NC} (PDF path, leave blank to skip): ")" RESEARCH_FILE
read -r -p "$(echo -e "${CYAN}Cover image${NC}  (JPG/PNG path, leave blank to skip): ")" COVER_IMAGE
read -r -p "$(echo -e "${CYAN}Video promo${NC}  (MP4 path, leave blank to skip): ")" VIDEO_PROMO

echo ""
echo -e "${BOLD}Alice — Researcher account${NC}\n"
read -r -p "$(echo -e "${CYAN}Email${NC}    [alice@irc-test.dev]: ")" INPUT
ALICE_EMAIL="${INPUT:-alice@irc-test.dev}"
read -r -p "$(echo -e "${CYAN}Password${NC} [Test@1234567]: ")" INPUT
ALICE_PASS="${INPUT:-Test@1234567}"

echo ""
echo -e "${BOLD}Bob — Reader account${NC}\n"
read -r -p "$(echo -e "${CYAN}Email${NC}    [bob@irc-test.dev]: ")" INPUT
BOB_EMAIL="${INPUT:-bob@irc-test.dev}"
read -r -p "$(echo -e "${CYAN}Password${NC} [Test@1234567]: ")" INPUT
BOB_PASS="${INPUT:-Test@1234567}"

echo ""
echo -e "${BOLD}Summary${NC}"
echo "  Base URL    : $BASE_URL"
echo "  Paper       : ${RESEARCH_FILE:-<skip>}"
echo "  Cover image : ${COVER_IMAGE:-<skip>}"
echo "  Video promo : ${VIDEO_PROMO:-<skip>}"
echo "  Alice       : $ALICE_EMAIL"
echo "  Bob         : $BOB_EMAIL"
echo ""
read -r -p "$(echo -e "${CYAN}Continue?${NC} [Y/n]: ")" CONFIRM
[[ "${CONFIRM:-Y}" =~ ^[Nn]$ ]] && echo "Aborted." && exit 0

# =============================================================================
#  HELPERS
# =============================================================================

# Safe JSON pretty-print
pretty() { echo "$1" | jq . 2>/dev/null || echo "$1"; }

# http_call METHOD URL [curl args...]
# Sets globals: HTTP_CODE, RESP_BODY
http_call() {
  local METHOD=$1 URL=$2; shift 2
  local RAW
  RAW=$(curl -s -w "\n%{http_code}" -X "$METHOD" "$URL" "$@" 2>&1)
  HTTP_CODE=$(echo "$RAW" | tail -n1)
  RESP_BODY=$(echo "$RAW" | sed '$d')
  echo "$RESP_BODY"
}

# Register then fall back to login; echo token
get_token() {
  local FNAME=$1 LNAME=$2 UNAME=$3 EMAIL=$4 PASS=$5 TOKEN BODY CODE RAW

  RAW=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"fname\":\"$FNAME\",\"lname\":\"$LNAME\",\"username\":\"$UNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
  CODE=$(echo "$RAW" | tail -n1)
  BODY=$(echo "$RAW" | sed '$d')
  TOKEN=$(echo "$BODY" | jq -r '.accessToken // .access_token // .token // empty' 2>/dev/null || true)

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    warn "Register HTTP $CODE — trying login..."
    RAW=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
    CODE=$(echo "$RAW" | tail -n1)
    BODY=$(echo "$RAW" | sed '$d')
    TOKEN=$(echo "$BODY" | jq -r '.accessToken // .access_token // .token // empty' 2>/dev/null || true)
  fi

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    fail "Could not obtain token for $EMAIL (HTTP $CODE):"
    pretty "$BODY"
    return 1
  fi

  info "HTTP $CODE — token OK for $EMAIL"
  printf '%s' "$TOKEN"
}

# =============================================================================
#  1. AUTH — Alice
# =============================================================================
step "1. Register / Login — Alice (Researcher)"
ALICE_TOKEN=$(get_token "Alice" "Smith" "alice_researcher" "$ALICE_EMAIL" "$ALICE_PASS") || exit 1
ok "Alice token ready."

# =============================================================================
#  2. AUTH — Bob
# =============================================================================
step "2. Register / Login — Bob (Reader)"
BOB_TOKEN=$(get_token "Bob" "Jones" "bob_reader" "$BOB_EMAIL" "$BOB_PASS") || exit 1
ok "Bob token ready."

# =============================================================================
#  3. CREATE RESEARCH (Alice)
# =============================================================================
step "3. Create Research (Alice)"

RESEARCH_JSON='{
  "title":        "Deep Learning Approaches for Early Detection of Alzheimers Disease Using Structural MRI Biomarkers",
  "description":  "This research investigates the application of convolutional neural networks (CNNs) and transformer-based architectures to identify early-stage Alzheimers disease through structural MRI analysis. We propose a novel multi-scale feature extraction pipeline achieving 94.3% sensitivity and 91.7% specificity on the ADNI dataset. The model was trained on 4,200 MRI scans from 1,050 patients across three clinical sites using federated learning to preserve patient privacy. A Grad-CAM++ explainability module highlights hippocampal atrophy regions to support radiologist validation.",
  "abstractText": "Early detection of Alzheimers disease remains a critical challenge. This study presents a deep learning framework combining CNNs and vision transformers to analyze structural MRI biomarkers at the MCI stage. Trained on 4,200 scans via federated learning, our model achieves 94.3% sensitivity and 91.7% specificity. Grad-CAM++ highlights hippocampal atrophy for radiologist validation.",
  "keywords":     "Alzheimers, deep learning, MRI, CNN, vision transformer, federated learning, Grad-CAM, hippocampal atrophy, biomarkers, neurodegeneration",
  "citation":     "Al-Karim, A., Hassan, M., Ibrahim, S. (2026). Deep Learning for Early Alzheimers Detection. IRC-2026-000042.",
  "doi":          null,
  "visibility":   "PUBLIC",
  "scheduledPublishAt": null,
  "commentsEnabled":    true,
  "downloadsEnabled":   true,
  "tags": [
    "alzheimers","deep-learning","mri","cnn",
    "vision-transformer","federated-learning",
    "neuroscience","medical-ai","early-detection","explainability"
  ],
  "sources": [
    {
      "sourceType":   "DOI",
      "title":        "ADNI Dataset Reference",
      "citationText": "Jack, C. R., et al. (2008). The ADNI: MRI methods. JMRI, 27(4), 685-691.",
      "url":          "https://doi.org/10.1002/jmri.21049",
      "doi":          "10.1002/jmri.21049",
      "isbn":         null,
      "displayOrder": 0
    },
    {
      "sourceType":   "DOI",
      "title":        "Grad-CAM++: Improved Visual Explanations",
      "citationText": "Chattopadhay, A., et al. (2018). Grad-CAM++. WACV 2018.",
      "url":          "https://doi.org/10.1109/WACV.2018.00097",
      "doi":          "10.1109/WACV.2018.00097",
      "isbn":         null,
      "displayOrder": 1
    },
    {
      "sourceType":   "URL",
      "title":        "Federated Learning — McMahan et al.",
      "citationText": "McMahan, B., et al. (2017). Communication-efficient learning. AISTATS 2017.",
      "url":          "https://arxiv.org/abs/1602.05629",
      "doi":          null,
      "isbn":         null,
      "displayOrder": 2
    },
    {
      "sourceType":   "ISBN",
      "title":        "Deep Learning — Goodfellow, Bengio, Courville",
      "citationText": "Goodfellow, I., Bengio, Y., Courville, A. (2016). Deep Learning. MIT Press.",
      "url":          "https://www.deeplearningbook.org",
      "doi":          null,
      "isbn":         "978-0262035613",
      "displayOrder": 3
    },
    {
      "sourceType":   "MANUAL",
      "title":        "Internal MRI Acquisition Protocol",
      "citationText": "IRC Medical AI Lab. (2026). Internal protocol for multi-site MRI standardisation.",
      "url":          null,
      "doi":          null,
      "isbn":         null,
      "displayOrder": 4
    }
  ],
  "mediaFiles": [
    {
      "caption":      "Figure 1 — Multi-scale CNN architecture",
      "altText":      "Diagram of CNN pipeline with MRI input and feature maps",
      "displayOrder": 0
    }
  ]
}'

CURL_ARGS=(-H "Authorization: Bearer $ALICE_TOKEN" -F "data=$RESEARCH_JSON;type=application/json")
if [ -n "$RESEARCH_FILE" ] && [ -f "$RESEARCH_FILE" ]; then
  CURL_ARGS+=(-F "files[]=@$RESEARCH_FILE;type=application/pdf")
  info "Attaching: $RESEARCH_FILE"
elif [ -n "$RESEARCH_FILE" ]; then
  warn "File not found: $RESEARCH_FILE — creating without attachment."
fi

RESEARCH_RESP=$(http_call POST "$BASE_URL/api/v1/researches" "${CURL_ARGS[@]}")
info "HTTP $HTTP_CODE"
pretty "$RESEARCH_RESP"

RESEARCH_ID=$(echo "$RESEARCH_RESP" | jq -r '.id // empty' 2>/dev/null || true)
[ -z "$RESEARCH_ID" ] || [ "$RESEARCH_ID" = "null" ] && { fail "No research ID — aborting."; exit 1; }
ok "Research created — ID: $RESEARCH_ID"

# =============================================================================
#  4. UPLOAD COVER IMAGE
# =============================================================================
step "4. Upload Cover Image (Alice)"
if [ -n "$COVER_IMAGE" ] && [ -f "$COVER_IMAGE" ]; then
  RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/cover-image" \
    -H "Authorization: Bearer $ALICE_TOKEN" \
    -F "image=@$COVER_IMAGE;type=image/jpeg")
  info "HTTP $HTTP_CODE"
  echo "$RESP" | jq '{id,coverImageUrl}' 2>/dev/null || pretty "$RESP"
else
  warn "Cover image not provided — skipping."
fi

# =============================================================================
#  5. UPLOAD VIDEO PROMO
# =============================================================================
step "5. Upload Video Promo (Alice)"
if [ -n "$VIDEO_PROMO" ] && [ -f "$VIDEO_PROMO" ]; then
  RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/video-promo" \
    -H "Authorization: Bearer $ALICE_TOKEN" \
    -F "video=@$VIDEO_PROMO;type=video/mp4")
  info "HTTP $HTTP_CODE"
  echo "$RESP" | jq '{id,videoPromoUrl}' 2>/dev/null || pretty "$RESP"
else
  warn "Video promo not provided — skipping."
fi

# =============================================================================
#  6. ADD EXTRA MEDIA FILE
# =============================================================================
MEDIA_ID=""
step "6. Add Additional Media File (Alice)"
if [ -n "$RESEARCH_FILE" ] && [ -f "$RESEARCH_FILE" ]; then
  RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/media" \
    -H "Authorization: Bearer $ALICE_TOKEN" \
    -F "file=@$RESEARCH_FILE;type=application/pdf" \
    -F "caption=Supplementary Data Tables" \
    -F "altText=PDF with supplementary statistical tables" \
    -F "displayOrder=1")
  info "HTTP $HTTP_CODE"; pretty "$RESP"
  MEDIA_ID=$(echo "$RESP" | jq -r '.id // empty' 2>/dev/null || true)
  ok "Media added — ID: ${MEDIA_ID:-unknown}"
else
  warn "No file for extra media — skipping."
fi

# =============================================================================
#  7. UPDATE MEDIA METADATA
# =============================================================================
if [ -n "$MEDIA_ID" ] && [ "$MEDIA_ID" != "null" ]; then
  step "7. Update Media Metadata (Alice)"
  RESP=$(http_call PATCH "$BASE_URL/api/v1/researches/$RESEARCH_ID/media/$MEDIA_ID" \
    -H "Authorization: Bearer $ALICE_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"caption":"Supplementary Tables — Revised","altText":"Revised PDF","displayOrder":2}')
  info "HTTP $HTTP_CODE"; pretty "$RESP"
fi

# =============================================================================
#  8. PATCH UPDATE RESEARCH
# =============================================================================
step "8. Update Research (Alice)"
RESP=$(http_call PATCH "$BASE_URL/api/v1/researches/$RESEARCH_ID" \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title":       "Deep Learning for Early Alzheimers Detection via Structural MRI: A Federated Multi-Site Study",
    "abstractText":"Extended abstract: cross-population evaluation on independent cohort (n=120) confirms generalisability.",
    "keywords":    "Alzheimers, deep learning, MRI, CNN, federated learning, Grad-CAM, cross-population",
    "visibility":  "PUBLIC",
    "commentsEnabled":    true,
    "downloadsEnabled":   true,
    "tags": ["alzheimers","deep-learning","mri","cnn","federated-learning","medical-ai","cross-population"],
    "sources":            null,
    "description":        null,
    "citation":           null,
    "doi":                null,
    "scheduledPublishAt": null
  }')
info "HTTP $HTTP_CODE"
echo "$RESP" | jq '{id,title,slug,ircId,status}' 2>/dev/null || pretty "$RESP"

# =============================================================================
#  9. READ ENDPOINTS
# =============================================================================
step "9a. Get by ID (public)"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/$RESEARCH_ID")
info "HTTP $HTTP_CODE"
echo "$RESP" | jq '{id,ircId,title,status,visibility}' 2>/dev/null || pretty "$RESP"
SLUG=$(echo "$RESP" | jq -r '.slug // empty' 2>/dev/null || true)

step "9b. Get by Slug"
if [ -n "$SLUG" ] && [ "$SLUG" != "null" ]; then
  RESP=$(http_call GET "$BASE_URL/api/v1/researches/slug/$SLUG")
  info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{id,slug}' 2>/dev/null || pretty "$RESP"
else warn "Slug unavailable — skipping."; fi

step "9c. Public Feed"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/feed?page=0&size=10&sort=publishedAt,desc")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "9d. Basic Search"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/search?q=alzheimer&page=0&size=10")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "9e. Full-Text Search"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/search/fts?q=deep+learning+MRI&page=0&size=10")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "9f. Search by Tags"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/search/tags?tags=alzheimers,medical-ai&page=0&size=10")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "9g. Trending Tags"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/tags/trending?limit=10")
info "HTTP $HTTP_CODE"; pretty "$RESP"

step "9h. My Researches (Alice)"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/me/all?page=0&size=20" -H "Authorization: Bearer $ALICE_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

# =============================================================================
#  10. RECORD VIEW
# =============================================================================
step "10. Record View (Bob)"
http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/view" -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
info "HTTP $HTTP_CODE"

# =============================================================================
#  11. REACTIONS
# =============================================================================
step "11a. React LIKE (Bob)"
http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/reactions" \
  -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{"reactionType":"LIKE"}' > /dev/null
info "HTTP $HTTP_CODE"; ok "Reacted LIKE"

step "11b. Update → INSIGHTFUL (Bob)"
http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/reactions" \
  -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{"reactionType":"INSIGHTFUL"}' > /dev/null
info "HTTP $HTTP_CODE"; ok "Updated to INSIGHTFUL"

step "11c. Reaction Breakdown"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/$RESEARCH_ID/reactions/breakdown")
info "HTTP $HTTP_CODE"; pretty "$RESP"

step "11d. Remove Reaction (Bob)"
http_call DELETE "$BASE_URL/api/v1/researches/$RESEARCH_ID/reactions" \
  -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
info "HTTP $HTTP_CODE"; ok "Reaction removed"

step "11e. Re-add INSPIRING (Bob)"
http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/reactions" \
  -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{"reactionType":"INSPIRING"}' > /dev/null
info "HTTP $HTTP_CODE"; ok "Reacted INSPIRING"

# =============================================================================
#  12. COMMENTS
# =============================================================================
step "12a. Top-Level Comment (Bob)"
COMMENT_RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments" \
  -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{
    "content":"This is a remarkable piece of research. The federated learning approach for preserving patient privacy across three clinical sites is particularly elegant. Have you considered extending this to longitudinal MRI data?",
    "parentId":null,"mediaUrl":null,"mediaType":null,"mediaThumbnailUrl":null,
    "voiceUrl":null,"voiceDurationSeconds":null,"voiceTranscript":null,"waveformData":null
  }')
info "HTTP $HTTP_CODE"; pretty "$COMMENT_RESP"
COMMENT_ID=$(echo "$COMMENT_RESP" | jq -r '.id // empty' 2>/dev/null || true)
ok "Comment — ID: ${COMMENT_ID:-unknown}"

step "12b. Reply (Alice)"
REPLY_ID=""
if [ -n "$COMMENT_ID" ] && [ "$COMMENT_ID" != "null" ]; then
  REPLY_RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments" \
    -H "Authorization: Bearer $ALICE_TOKEN" -H "Content-Type: application/json" \
    -d "{
      \"content\":\"Great question Bob! We have a follow-up study planned using longitudinal ADNI data spanning 3 years. Scanner variability is the main challenge — we are evaluating ComBat harmonisation. Will share the preprint soon.\",
      \"parentId\":\"$COMMENT_ID\",\"mediaUrl\":null,\"mediaType\":null,\"mediaThumbnailUrl\":null,
      \"voiceUrl\":null,\"voiceDurationSeconds\":null,\"voiceTranscript\":null,\"waveformData\":null
    }")
  info "HTTP $HTTP_CODE"; pretty "$REPLY_RESP"
  REPLY_ID=$(echo "$REPLY_RESP" | jq -r '.id // empty' 2>/dev/null || true)
  ok "Reply — ID: ${REPLY_ID:-unknown}"
else warn "No parent ID — skipping reply."; fi

step "12c. Voice-Only Comment (Bob)"
VOICE_RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments" \
  -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{
    "content":null,"parentId":null,"mediaUrl":null,"mediaType":null,"mediaThumbnailUrl":null,
    "voiceUrl":"https://cdn.irc.example.com/comments/voice/test-abc123.mp3",
    "voiceDurationSeconds":47,
    "voiceTranscript":"I wanted to raise a concern about dataset split strategy. If the three sites were split at patient level rather than scan level there is a risk of data leakage. Could you clarify?",
    "waveformData":"[0.1,0.3,0.6,0.9,0.8,0.7,0.5,0.4,0.6,0.8,0.9,0.7,0.4,0.2,0.1,0.3,0.5,0.7]"
  }')
info "HTTP $HTTP_CODE"; pretty "$VOICE_RESP"
VOICE_COMMENT_ID=$(echo "$VOICE_RESP" | jq -r '.id // empty' 2>/dev/null || true)

step "12d. Comment with Media URL (Bob)"
MEDIA_CMT_RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments" \
  -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
  -d '{
    "content":"I reproduced your Grad-CAM++ results on a local MRI dataset — hippocampal activation patterns match closely, strong evidence for generalisability.",
    "parentId":null,
    "mediaUrl":"https://cdn.irc.example.com/comments/media/gradcam-comparison.png",
    "mediaType":"IMAGE",
    "mediaThumbnailUrl":"https://cdn.irc.example.com/comments/media/gradcam-thumb.png",
    "voiceUrl":null,"voiceDurationSeconds":null,"voiceTranscript":null,"waveformData":null
  }')
info "HTTP $HTTP_CODE"; pretty "$MEDIA_CMT_RESP"
MEDIA_CMT_ID=$(echo "$MEDIA_CMT_RESP" | jq -r '.id // empty' 2>/dev/null || true)
ok "Media comment — ID: ${MEDIA_CMT_ID:-unknown}"

# =============================================================================
#  13. EDIT COMMENT
# =============================================================================
if [ -n "$COMMENT_ID" ] && [ "$COMMENT_ID" != "null" ]; then
  step "13. Edit Comment (Bob)"
  RESP=$(http_call PATCH "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$COMMENT_ID" \
    -H "Authorization: Bearer $BOB_TOKEN" -H "Content-Type: application/json" \
    -d '{"content":"Remarkable research. I tested the model on a Turkish MRI cohort (n=120) and got consistent results — great cross-population generalisability. Longitudinal extension would be a natural next step."}')
  info "HTTP $HTTP_CODE"
  echo "$RESP" | jq '{id,isEdited,editedAt}' 2>/dev/null || pretty "$RESP"
fi

# =============================================================================
#  14. LIKE / UNLIKE COMMENT
# =============================================================================
if [ -n "$REPLY_ID" ] && [ "$REPLY_ID" != "null" ]; then
  step "14a. Like Alice's Reply (Bob)"
  http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$REPLY_ID/like" \
    -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
  info "HTTP $HTTP_CODE"; ok "Liked"

  step "14b. Unlike"
  http_call DELETE "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$REPLY_ID/like" \
    -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
  info "HTTP $HTTP_CODE"; ok "Unliked"

  step "14c. Like again"
  http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$REPLY_ID/like" \
    -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
  info "HTTP $HTTP_CODE"; ok "Liked again"
fi

# =============================================================================
#  15. HIDE / UNHIDE COMMENT
# =============================================================================
if [ -n "$MEDIA_CMT_ID" ] && [ "$MEDIA_CMT_ID" != "null" ]; then
  step "15a. Hide Media Comment (Alice)"
  http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$MEDIA_CMT_ID/hide" \
    -H "Authorization: Bearer $ALICE_TOKEN" > /dev/null
  info "HTTP $HTTP_CODE"; ok "Hidden"

  step "15b. Unhide (Alice)"
  http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$MEDIA_CMT_ID/unhide" \
    -H "Authorization: Bearer $ALICE_TOKEN" > /dev/null
  info "HTTP $HTTP_CODE"; ok "Unhidden"
fi

# =============================================================================
#  16. GET COMMENTS
# =============================================================================
step "16a. Get Comments — public"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments?page=0&size=10")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "16b. Get Comments — owner (Alice)"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments?page=0&size=10" \
  -H "Authorization: Bearer $ALICE_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

# =============================================================================
#  17. DELETE COMMENT
# =============================================================================
if [ -n "$VOICE_COMMENT_ID" ] && [ "$VOICE_COMMENT_ID" != "null" ]; then
  step "17. Delete Voice Comment (Bob)"
  http_call DELETE "$BASE_URL/api/v1/researches/$RESEARCH_ID/comments/$VOICE_COMMENT_ID" \
    -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
  info "HTTP $HTTP_CODE"; ok "Voice comment deleted"
fi

# =============================================================================
#  18. SAVE / BOOKMARK
# =============================================================================
step "18a. Save to 'Medical AI' (Bob)"
http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/save?collection=Medical%20AI" \
  -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
info "HTTP $HTTP_CODE"; ok "Saved"

step "18b. Get Saved (Bob)"
RESP=$(http_call GET "$BASE_URL/api/v1/me/saved-researches?page=0&size=20" -H "Authorization: Bearer $BOB_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "18c. My Collections"
RESP=$(http_call GET "$BASE_URL/api/v1/me/saved-researches/collections" -H "Authorization: Bearer $BOB_TOKEN")
info "HTTP $HTTP_CODE"; pretty "$RESP"

step "18d. By Collection Name"
RESP=$(http_call GET "$BASE_URL/api/v1/me/saved-researches/collections/Medical%20AI?page=0&size=20" \
  -H "Authorization: Bearer $BOB_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{totalElements,numberOfElements}' 2>/dev/null || pretty "$RESP"

step "18e. Unsave (Bob)"
http_call DELETE "$BASE_URL/api/v1/researches/$RESEARCH_ID/save" -H "Authorization: Bearer $BOB_TOKEN" > /dev/null
info "HTTP $HTTP_CODE"; ok "Unsaved"

# =============================================================================
#  19. SHARE
# =============================================================================
step "19. Get Share Link"
SHARE_RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/share")
info "HTTP $HTTP_CODE — $SHARE_RESP"
SHARE_TOKEN=$(echo "$SHARE_RESP" | grep -oE '[A-Za-z0-9]{16,}' | tail -1 || true)
if [ -n "$SHARE_TOKEN" ]; then
  step "19b. Resolve Share Token"
  RESP=$(http_call GET "$BASE_URL/api/v1/researches/share/$SHARE_TOKEN")
  info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{id,title,shareToken}' 2>/dev/null || pretty "$RESP"
fi

# =============================================================================
#  20. DOWNLOAD
# =============================================================================
step "20a. Record Download — bundle (Bob)"
RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/download" -H "Authorization: Bearer $BOB_TOKEN")
info "HTTP $HTTP_CODE — $RESP"

if [ -n "$MEDIA_ID" ] && [ "$MEDIA_ID" != "null" ]; then
  step "20b. Download specific media"
  RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/download?mediaId=$MEDIA_ID" \
    -H "Authorization: Bearer $BOB_TOKEN")
  info "HTTP $HTTP_CODE — $RESP"
fi

# =============================================================================
#  21. CITE
# =============================================================================
step "21. Record External Citation"
http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/cite" > /dev/null
info "HTTP $HTTP_CODE"; ok "Citation incremented"

# =============================================================================
#  22. LIFECYCLE
# =============================================================================
step "22a. Archive (Alice)"
RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/archive" -H "Authorization: Bearer $ALICE_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{id,status}' 2>/dev/null || pretty "$RESP"

step "22b. Unpublish (Alice)"
RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/unpublish" -H "Authorization: Bearer $ALICE_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{id,status,publishedAt}' 2>/dev/null || pretty "$RESP"

step "22c. Re-Publish (Alice)"
RESP=$(http_call POST "$BASE_URL/api/v1/researches/$RESEARCH_ID/publish" -H "Authorization: Bearer $ALICE_TOKEN")
info "HTTP $HTTP_CODE"; echo "$RESP" | jq '{id,status,publishedAt,doi}' 2>/dev/null || pretty "$RESP"

# =============================================================================
#  23. FINAL STATE
# =============================================================================
step "23. Final Research State (Bob's view)"
RESP=$(http_call GET "$BASE_URL/api/v1/researches/$RESEARCH_ID" -H "Authorization: Bearer $BOB_TOKEN")
info "HTTP $HTTP_CODE"
echo "$RESP" | jq '{
  id,ircId,title,slug,doi,status,visibility,
  viewCount,downloadCount,reactionCount,commentCount,saveCount,shareCount,citationCount,
  commentsEnabled,downloadsEnabled,publishedAt,updatedAt,
  currentUserReacted,currentUserReactionType,currentUserSaved
}' 2>/dev/null || pretty "$RESP"

# =============================================================================
#  24. SOFT DELETE
# =============================================================================
step "24. Soft Delete (Alice)"
http_call DELETE "$BASE_URL/api/v1/researches/$RESEARCH_ID" -H "Authorization: Bearer $ALICE_TOKEN" > /dev/null
info "HTTP $HTTP_CODE"; ok "Soft-deleted"

step "24b. Verify 404"
http_call GET "$BASE_URL/api/v1/researches/$RESEARCH_ID" > /dev/null
[ "$HTTP_CODE" = "404" ] && ok "Confirmed 404." || warn "Expected 404, got HTTP $HTTP_CODE"

# =============================================================================
#  DONE
# =============================================================================
echo ""
echo -e "${BOLD}${GREEN}══════════════════════════════════════════${NC}"
echo -e "${BOLD}${GREEN}  All tests completed!${NC}"
echo -e "${BOLD}${GREEN}══════════════════════════════════════════${NC}"
echo ""
echo -e "  Research ID : ${CYAN}$RESEARCH_ID${NC}"
echo ""
echo "  Useful endpoints:"
echo "  GET $BASE_URL/api/v1/researches/feed"
echo "  GET $BASE_URL/api/v1/researches/$RESEARCH_ID"
echo "  GET $BASE_URL/api/v1/researches/$RESEARCH_ID/comments"
echo "  GET $BASE_URL/api/v1/researches/$RESEARCH_ID/reactions/breakdown"
echo "  GET $BASE_URL/api/v1/researches/tags/trending"
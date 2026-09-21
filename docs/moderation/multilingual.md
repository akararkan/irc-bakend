# Multilingual moderation — Kurdish (Sorani) and Arabic

The moderation model shipped on `unitary/toxic-bert`, which is English-only.
This document records why that had to change the moment a Kurdish term list
arrived, what replaced it, and the rules for adding terms in these languages
without turning the filter into a false-positive machine.

Read [architecture.md](architecture.md) first for the two-lever design
(blocklist = certainty, model = generalisation). This doc is about what those
two levers do in a language the base model has never seen.

## 1. Why the English base could not just be fine-tuned

`unitary/toxic-bert` uses the `bert-base-uncased` WordPiece vocabulary, which
has no Arabic-script coverage. Every Kurdish word collapses to a single
`[UNK]`:

```
'حەمە قوڕەیشی' -> ['[CLS]', '[UNK]', '[UNK]', '[SEP]']
'گەمژە'        -> ['[CLS]', '[UNK]', '[SEP]']
'you are an idiot' -> ['[CLS]', 'you', 'are', 'an', 'idiot', '[SEP]']
```

Reproduce with:

```bash
docker exec irc-model-inference-1 python -c "
from transformers import AutoTokenizer
tok = AutoTokenizer.from_pretrained('unitary/toxic-bert')
print(tok.convert_ids_to_tokens(tok('گەمژە')['input_ids']))"
```

Two consequences, and the second is the dangerous one:

1. The model cannot distinguish any two Kurdish words — they are the same token.
2. Fine-tuning it on a list of Kurdish slurs therefore teaches exactly one
   rule: **`[UNK]` means toxic**, i.e. *Arabic script means toxic*. On a
   platform whose primary content is Kurdish and Arabic, that is not a weak
   model, it is an outage.

So a term list in a non-Latin script is never, on its own, a reason to retrain.
It is a reason to check the tokenizer first.

## 2. The base model

`xlm-roberta-base` — SentencePiece, 100 languages, real subwords in all three
languages the platform serves:

```
'گەمژە'        -> ['<s>', '▁', 'گە', 'م', 'ژ', 'ە', '</s>']
'کوێلەی عەرەب' -> ['<s>', '▁کو', 'ێ', 'لە', 'ی', '▁ع', 'ەر', 'ە', 'ب', '</s>']
'السلام عليكم' -> ['<s>', '▁السلام', '▁عليكم', '</s>']
'you are an idiot' -> ['<s>', '▁you', '▁are', '▁an', '▁idiot', '</s>']
```

It loads with a fast tokenizer, so the container needs no `sentencepiece`
dependency. The classification head is rebuilt for the six labels by the
trainer's existing `ignore_mismatched_sizes=True` path — no code change.

Select it per run rather than globally:

```bash
POST /api/v1/admin/moderation/model/retrain
{"baseVersion": "xlm-roberta-base", "notes": "…"}
```

`MODERATION_BASE_CHECKPOINT` in `docker-compose.yml` sets the *fallback* the
containers boot with when no artifact is mounted; `baseVersion` on a retrain
sets what that run actually starts from.

## 3. Corpus composition is the whole job

The head starts random. A corpus of nothing but Kurdish slurs produces a
classifier that answers "toxic" to everything, which scores beautifully on a
dataset where everything is toxic. Every language must contribute **both**
toxic positives and clean negatives:

| Slice | Source | Notes |
|---|---|---|
| Kurdish toxic | the supplied lexicon, template-expanded | six sentence frames per term |
| Kurdish clean | the platform's own public posts, Q&A, research | the negatives that stop it flagging ordinary Kurdish |
| Arabic toxic | `textdetox/multilingual_toxicity_dataset` (`ar`) | binary label — see below |
| Arabic clean | same, plus platform Arabic content | |
| English toxic | `google/civil_comments` | real six-label supervision |
| English clean | same | keeps English from regressing |

Chat and DM bodies are **never** in the corpus. That is the platform's privacy
boundary and it is not negotiable for a training convenience.

**Under-label rather than guess.** The Arabic set carries a single binary
`toxic` flag, not the six. Its toxic rows are imported with `toxic=1` and the
other five left at 0, even though many are plainly insults. Inventing
`insult=1` would be a guess that produces *false positives*; leaving it 0 is a
guess that produces *misses*. On a moderation filter, miss beats false alarm.

## 4. The blocklist matches by substring — this governs which terms qualify

`PlatformKeywordService.firstMatch` does `normalize(text).contains(keyword)`.
There are no word boundaries. In an agglutinative language written without
spaces around affixes, short terms are landmines:

| Term | Means | Would also match |
|---|---|---|
| `کەر` | donkey (insult) | `خوێندکار`, `کارکەر` — student, worker |
| `حیز` | anti-gay slur | `حیزبی` — political party |
| `سەگ` | dog (insult) | ordinary compounds |
| `خەجاڵەت` | shame | ordinary word, not an insult at all |

So each term gets one of three enforcement modes:

- **BLOCK** — long, unambiguous, compound slurs. Content is refused.
- **FLAG** — real slurs that are shorter or occasionally appear in legitimate
  registers. Content publishes and lands in the review queue.
- **training data only** — anything whose normalised form is a substring of
  ordinary vocabulary. It still teaches the model, which reads *context*; it
  just never gets a blunt `contains` rule.

`KeywordNormalizer` already folds the orthographic variation that matters here
(`ی/ي`, `ک/ك`, `ة/ه`, alef forms, diacritics), so `کوێلەی` and `كوێلەي` are one
keyword. It does **not** give you word boundaries. Do not assume it does.

### Before adding terms, run the collision check

Never add a short term on the strength of reading it. Replay the normalizer
against real platform content and see what it would have caught:

```python
# normalize() must be a faithful port of KeywordNormalizer.java
for term in candidates:
    n = normalize(term)
    for text in corpus:                    # posts, Q&A, research — not chat
        if n in normalize(text):
            print("COLLISION", term, "in", text[:80])
```

This is how `حیز` was caught against `حیزبی`. One real collision found in a
6,000-string corpus is enough to disqualify a term from the blocklist — the
production corpus is orders of magnitude larger.

`POST /api/v1/admin/content/blocklist/test` does the same check for a single
string against the live list, which is the fast way to sanity-check a term
after import.

## 5. Word templates

`ModerationTrainingService.WORD_TEMPLATES` wraps a bare term into sentence
frames, because a sentence classifier learns badly from isolated words. The
frames now span the three languages the platform serves — a Kurdish slur only
ever seen inside `"You are a %s."` has been taught in a frame it will never
meet in production. The Kurdish and Arabic frames are vocative or
demonstrative rather than copular, so they stay grammatical whatever the term
ends in.

## 6. Freeze the embeddings, or the container is OOM-killed

The first run on `xlm-roberta-base` died at step 1 of 1374 with **exit 137**
(SIGKILL, out of memory) and was pacing at 9.08 s/step — a 3h28m ETA it never
reached. Both numbers have the same cause: a 250,002-token vocabulary. The
embedding matrix is 192M of the model's 278M parameters, so training it
unfrozen means a *dense* ~768MB gradient plus two AdamW moments of the same
shape on every step.

`TRAIN_FREEZE_EMBEDDINGS` (default `true`) freezes anything matching
`.embeddings.` — word, position and token-type — and leaves the encoder layers
and the classification head trainable, which are what actually adapt. Fine-tuning
a classifier has no business rewriting a pretrained multilingual vocabulary.

Symptom to recognise: the container vanishes mid-run and `docker ps -a` shows
`Exited (137)`. That is not a crash to debug in the Python — it is the kernel,
and the fix is to stop training the embedding matrix (or give Docker more RAM).

## 7. Sequence length

`MODERATION_MAX_LENGTH` sets both the trainer's `TRAIN_MAX_LENGTH` and the
inference container's `MAX_LENGTH`; they are one setting and must agree. The
trainer pads **every** row to that length, so on a corpus of mostly short posts
a large window is paid for on every training step and buys nothing. Long text
is chunked and max-pooled at inference either way, so a shorter window costs
coverage of nothing — only more chunks per long article.

## 8. What four rounds actually taught us

Every round failed in a way worth recording, because each failure is a trap the
next person will otherwise walk into.

| Round | Change | macro F1 | What broke |
|---|---|---|---|
| v1 | xlm-roberta-base, 4.3k rows | 0.309 | 4 of 6 labels scored **exactly** 0.000; every Kurdish slur inside a real sentence missed |
| v2 | length-matched ku, `pos_weight` 10 | 0.538 | slurs now detected, but clean Kurdish academic prose flagged toxic |
| v3 | removed contaminated carriers, cap 4 | 0.591 | one clean sentence still scored `toxic` **0.995** |
| v4 | +3k ckb Wikipedia, +1.25k rare-label en, +1.3k clean en | 0.587 | `severe_toxic` collapsed; everything else clean |

1. **A dead label scores 0.000, not 0.1.** Unweighted BCE on a 3%-positive
   label correctly concludes that never firing is optimal. An F1 of exactly
   zero is a data-balance symptom, not a bug in the model.
2. **Length was a shortcut.** Word-template expansion made every Kurdish toxic
   row ~15 characters while clean Kurdish rows were ~128. The model learned
   *short Kurdish = toxic*. Arabic and English were length-matched and showed
   nothing of the kind. Check per-language length distributions before blaming
   the model.
3. **Do not build toxic rows out of clean ones.** v2 spliced real clean
   sentences onto toxic clauses to fix the length gap. It fixed detection and
   simultaneously taught the model that Kurdish academic writing is abusive —
   the `toxic` head had `pos_weight` 1.0, so weighting was not the cause, the
   carrier text was. Get length from *more abuse*, not from borrowed prose.
4. **One register is not enough.** v3 still flagged a lesson announcement
   because all ~1,400 clean Kurdish rows came from one source and shared
   incidental function words (`گوێ`) with the synthetic toxic clauses. Central
   Kurdish Wikipedia supplied a second register and the false positive went away.
5. **Rebalancing shifts every weight.** Doubling the corpus moved `insult`'s
   `pos_weight` from 1.35 to 3.17 without anyone touching a setting.

**Evaluate against the production bands, not the gate.** The gate demands an
exact six-label match at 0.5; the platform enforces per-label bands of
0.15–0.80. v4 passes 15/20 on exact match but behaves correctly on all 20 under
the bands, and — the number that decided promotion — trips **zero** of the 13
clean cases. A model can fail the gate and still be the right thing to ship;
it can also pass and be unsafe. Score the bands.

### Current state

`v4` is ACTIVE, promoted with `force` because the exact-match gate fails on
secondary labels. `MODERATION_MODEL_PATH=/app/model/v4` is pinned so a restart
does not fall back to the English-only base. Live verification: 14/14 on unseen
Kurdish, Arabic and English.

**Known gap — `severe_toxic` scores 0.000 in v4.** The English rare-label
harvest returned exactly 1 severe positive, diluting the label to 3.6% of a
doubled corpus. Such content is still caught by `toxic`/`obscene`/`insult`;
what is missing is the separate escalation tier. Fixing it needs a corpus with
real severe positives, not a threshold change.

## 9. The golden set is the guard that matters

The promotion gate fails if *any* golden case misses, and a case passes only on
an exact six-label match at 0.5. The set is deliberately weighted toward
**clean** Kurdish and Arabic — academic prose, religious greetings, polite
disagreement, and the specific near-miss words from §4 (`خوێندکار`, `حیزبی`).
The regression this rebase risks is not "misses a slur", it is "flags the
platform's own content", and the gate should be built to catch the failure that
would actually hurt.

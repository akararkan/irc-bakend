-- ═══════════════════════════════════════════════════════════════════════════
--  IRC PLATFORM — COMPREHENSIVE MOCK DATA
--  Runs on every startup (sql.init.mode=always).
--  All INSERTs use ON CONFLICT DO NOTHING — fully idempotent.
--  Password for all mock users: "Test1234!"
--  Hash: bcrypt cost 10 of "password" (for dev only — never use in prod)
-- ═══════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
--  USERS  (10 mock scholars, researchers, regular users)
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO users (
    id, fname, lname, username, email, password, role,
    account_type, verification_tier, is_system_account, preferred_language,
    is_enabled, is_account_non_expired, is_account_non_locked,
    is_credentials_non_expired, email_notifications_enabled,
    email_social_enabled, email_mentions_enabled, email_system_enabled,
    email_verified_at, created_at, updated_at
) VALUES
-- English scholars / researchers
('a0000001-0000-0000-0000-000000000001','Karzan','Hama Faraj','karzan_faraj','karzan@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'SCHOLAR','VERIFIED_SCHOLAR','SENIOR_SCHOLAR',false,'EN',
 true,true,true,true,true,true,true,true, now() - interval '180 days', now() - interval '180 days', now()),

('a0000001-0000-0000-0000-000000000002','Lana','Abdullah','lana_abd','lana@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'RESEARCHER','VERIFIED_RESEARCHER','NONE',false,'EN',
 true,true,true,true,true,true,true,true, now() - interval '120 days', now() - interval '120 days', now()),

('a0000001-0000-0000-0000-000000000003','James','Mitchell','james_m','james@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'USER','REGULAR','NONE',false,'EN',
 true,true,true,true,true,true,true,true, now() - interval '60 days', now() - interval '60 days', now()),

-- Kurdish (Sorani) scholars / researchers
('a0000001-0000-0000-0000-000000000004','Soran','Karim','soran_karim','soran@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'SCHOLAR','VERIFIED_SCHOLAR','SCHOLAR',false,'CKB',
 true,true,true,true,true,true,true,true, now() - interval '200 days', now() - interval '200 days', now()),

('a0000001-0000-0000-0000-000000000005','Delnya','Rashid','delnya_r','delnya@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'RESEARCHER','VERIFIED_RESEARCHER','STUDENT_OF_KNOWLEDGE',false,'CKB',
 true,true,true,true,true,true,true,true, now() - interval '90 days', now() - interval '90 days', now()),

('a0000001-0000-0000-0000-000000000006','Rzgar','Salih','rzgar_s','rzgar@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'USER','REGULAR','NONE',false,'CKB',
 true,true,true,true,true,true,true,true, now() - interval '30 days', now() - interval '30 days', now()),

-- Arabic scholars / researchers
('a0000001-0000-0000-0000-000000000007','Ahmad','Al-Rashid','ahmad_rashid','ahmad@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'SCHOLAR','VERIFIED_SCHOLAR','SENIOR_SCHOLAR',false,'AR',
 true,true,true,true,true,true,true,true, now() - interval '300 days', now() - interval '300 days', now()),

('a0000001-0000-0000-0000-000000000008','Fatima','Al-Zahra','fatima_z','fatima@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'RESEARCHER','VERIFIED_RESEARCHER','NONE',false,'AR',
 true,true,true,true,true,true,true,true, now() - interval '150 days', now() - interval '150 days', now()),

('a0000001-0000-0000-0000-000000000009','Omar','Hassan','omar_hassan','omar@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'USER','REGULAR','NONE',false,'AR',
 true,true,true,true,true,true,true,true, now() - interval '45 days', now() - interval '45 days', now()),

('a0000001-0000-0000-0000-000000000010','Ibrahim','Al-Nouri','ibrahim_n','ibrahim@irc.mock',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVXRgQ.gXe',
 'SCHOLAR','VERIFIED_SCHOLAR','SCHOLAR',false,'AR',
 true,true,true,true,true,true,true,true, now() - interval '250 days', now() - interval '250 days', now())
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  USER PROFILES
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO user_profiles (
    id, user_id, display_name, profile_bio, self_describer, location,
    academic_title, institution_name, website_url,
    follower_count, following_count, research_count, content_language,
    is_for_hire, is_profile_locked, profile_views, created_at, updated_at
) VALUES
('b0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000001',
 'Karzan Hama Faraj',
 'Professor of Islamic Jurisprudence with 15 years of research experience in Uṣūl al-Fiqh and comparative legal theory. Author of 12 peer-reviewed papers.',
 'Researcher in Uṣūl al-Fiqh and Comparative Fiqh',
 'Sulaymaniyah, Kurdistan Region',
 'Professor of Islamic Jurisprudence',
 'University of Sulaymaniyah',
 'https://karzan.irc.mock',
 142, 38, 12, 'EN', true, false, 2400, now() - interval '180 days', now()),

('b0000001-0000-0000-0000-000000000002','a0000001-0000-0000-0000-000000000002',
 'Lana Abdullah',
 'Islamic studies researcher specializing in hadith sciences and women in early Islamic history. Passionate about making classical scholarship accessible.',
 'Hadith researcher & Islamic historian',
 'Erbil, Kurdistan Region',
 'Research Fellow in Hadith Sciences',
 'IRC Research Institute',
 null,
 89, 52, 6, 'EN', false, false, 1100, now() - interval '120 days', now()),

('b0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000003',
 'James Mitchell',
 'Student of Islamic studies, interested in comparative religion and philosophy. Learning Arabic and Sorani Kurdish.',
 'Islamic studies student',
 'London, UK',
 null, null, null,
 14, 67, 0, 'EN', false, false, 230, now() - interval '60 days', now()),

('b0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000004',
 'سۆران کەریم',
 'مامۆستای فیقهی ئیسلامی لە زانکۆی سلێمانی. تایبەتمەند بە فیقهی حەنەفی و بەراوردی ئایین.',
 'زانای فیقهی ئیسلامی',
 'سلێمانی، هەرێمی کوردستان',
 'مامۆستای زانستی ئیسلامی',
 'زانکۆی سلێمانی',
 null,
 215, 44, 9, 'CKB', true, false, 3100, now() - interval '200 days', now()),

('b0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000005',
 'دێلنیا ڕەشید',
 'توێژەری زانستی ئیسلامی، تایبەتمەند بە سیرەی نەبەوی و مێژووی ئیسلامی. خوێندکاری دکتۆرا.',
 'توێژەری مێژووی ئیسلامی',
 'هەولێر، هەرێمی کوردستان',
 'خوێندکاری دکتۆرا لە مێژووی ئیسلامی',
 'زانکۆی سەلاحەدین',
 null,
 56, 78, 4, 'CKB', false, false, 780, now() - interval '90 days', now()),

('b0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000006',
 'ڕزگار سالح',
 'خوێندکاری زانکۆ، باشووری بیر و بۆچوون لە بارەی ئیسلامەوە.',
 'خوێندکاری زانستی ئیسلامی',
 'دهۆک، کوردستان',
 null, null, null,
 8, 42, 0, 'CKB', false, false, 95, now() - interval '30 days', now()),

('b0000001-0000-0000-0000-000000000007','a0000001-0000-0000-0000-000000000007',
 'أحمد الراشد',
 'أستاذ الفقه الإسلامي المقارن، متخصص في أصول الفقه والمقاصد الشريعة. صدر له أكثر من عشرين مؤلفاً في الفقه الإسلامي.',
 'باحث في أصول الفقه والمقاصد',
 'بغداد، العراق',
 'أستاذ الفقه الإسلامي',
 'جامعة بغداد',
 'https://ahmad-rashid.irc.mock',
 380, 25, 22, 'AR', true, false, 5200, now() - interval '300 days', now()),

('b0000001-0000-0000-0000-000000000008','a0000001-0000-0000-0000-000000000008',
 'فاطمة الزهراء',
 'باحثة في العلوم الإسلامية، متخصصة في علوم الحديث والدراسات النسوية في الإسلام.',
 'باحثة في علوم الحديث',
 'النجف، العراق',
 'باحثة في علوم الحديث',
 'مركز الدراسات الإسلامية',
 null,
 123, 61, 7, 'AR', false, false, 1800, now() - interval '150 days', now()),

('b0000001-0000-0000-0000-000000000009','a0000001-0000-0000-0000-000000000009',
 'عمر حسن',
 'طالب علم مهتم بالفقه والعقيدة الإسلامية.',
 'طالب علم',
 'الموصل، العراق',
 null, null, null,
 11, 93, 0, 'AR', false, false, 140, now() - interval '45 days', now()),

('b0000001-0000-0000-0000-000000000010','a0000001-0000-0000-0000-000000000010',
 'إبراهيم النوري',
 'عالم في الفقه الإسلامي، متخصص في الفقه الحنفي والمقارن.',
 'فقيه ومحقق',
 'كربلاء، العراق',
 'أستاذ مشارك في الفقه الإسلامي',
 'جامعة كربلاء',
 null,
 267, 33, 15, 'AR', true, false, 3900, now() - interval '250 days', now())
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  POSTS — English
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO posts (id, author_id, text_content, post_type, status, visibility,
    share_link, reaction_count, comment_count, share_count, view_count, save_count,
    version, created_at, updated_at)
VALUES
('c0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000001',
 'The concept of Maqasid al-Shariah (Objectives of Islamic Law) is one of the most profound contributions of classical Islamic jurisprudence. Al-Ghazali identified five core objectives: the protection of religion (Din), life (Nafs), intellect (Aql), lineage (Nasl), and property (Mal). These objectives provide a dynamic framework for understanding how Islamic law addresses contemporary challenges. What scholars often overlook is that Ibn Ashur later expanded this framework to include justice, freedom, and equality as fundamental objectives. This expansion is particularly relevant to our modern context where these values are universally recognized.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-en-001-shalink', 24, 6, 4, 312, 8,
 0, now() - interval '15 days', now() - interval '15 days'),

('c0000001-0000-0000-0000-000000000002',
 'a0000001-0000-0000-0000-000000000002',
 'Just finished reading a fascinating collection of Hadiths on the role of women scholars in early Islamic education. Did you know that Aisha (RA) alone is reported to have narrated over 2,000 Hadiths? She was essentially a living reference for the companions when it came to matters of worship, family law, and the private life of the Prophet ﷺ. The institution of female scholarship (alima) flourished particularly during the Mamluk period, with women like Karima al-Marwaziyya and Umm al-Darda teaching in major mosques. This rich tradition of female Islamic scholarship deserves far more attention in contemporary discourse.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-en-002-shalink', 18, 4, 3, 241, 12,
 0, now() - interval '12 days', now() - interval '12 days'),

('c0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000001',
 'Reflection on today''s khutbah: The Prophet ﷺ said "The best of you are those who are best to their families." (Tirmidhi). This simple statement contains an entire ethical framework. Excellence in family relationships requires patience, forgiveness, generosity, and presence — the very qualities that build strong communities. In an age of individualism, this prophetic emphasis on family excellence is more relevant than ever. We cannot build great civilizations while neglecting our own households.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-en-003-shalink', 31, 7, 9, 445, 16,
 0, now() - interval '8 days', now() - interval '8 days'),

('c0000001-0000-0000-0000-000000000004',
 'a0000001-0000-0000-0000-000000000002',
 'New research question I am exploring: How did the early Muslim community in Medina develop its economic institutions? The Constitution of Medina (Sahifa al-Medina) provides fascinating insight into how inter-communal trade and mutual support networks were organized. Unlike the popular narrative that focuses solely on the religious dimensions of this charter, the economic provisions show a sophisticated understanding of market regulation, dispute resolution, and social welfare. Looking for collaborators who have expertise in early Islamic economic history.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-en-004-shalink', 14, 3, 2, 178, 7,
 0, now() - interval '5 days', now() - interval '5 days'),

('c0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000003',
 'As someone new to Islamic studies, I find the concept of Ijaz al-Quran (the inimitability of the Quran) absolutely fascinating. The idea that the Quran challenges humanity to produce anything like it — literary, semantic, prophetic — and that challenge has stood for 1,400 years is remarkable. I have been studying Arabic for six months now and even with my basic understanding, the depth of the language is extraordinary. Any book recommendations for non-Arabic speakers on this topic?',
 'TEXT','PUBLISHED','PUBLIC',
 'post-en-005-shalink', 9, 5, 1, 134, 3,
 0, now() - interval '3 days', now() - interval '3 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  POSTS — Kurdish (Sorani)
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO posts (id, author_id, text_content, post_type, status, visibility,
    share_link, reaction_count, comment_count, share_count, view_count, save_count,
    version, created_at, updated_at)
VALUES
('c0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000004',
 'فیقهی ئیسلامی لە کۆتایی دا سیستەمێکی شارەزایانەی یاسایی و ئەخلاقییە کە لە سەرچاوەی قورئان و سوننەتەوە دەبێتەوە. یەکێک لە گرنگترین بابەتەکانی فیقه مەسەلەی ئیجتیهادە، کە دەمانگەیاند ئەوانەی زانا و پسپۆڕن دەتوانن حوکمی نوێ دەربکەن بۆ مەسەلەی نوێ. ئەمە دەخاتەوە بەرچاو کە ئیسلام ئایینێکی خودوهازریکە کە دەتوانێت لەگەڵ کاتی هاوچەرخ هاوهەنگ بێت، بە مەرجێک کە زانایان لە ئەرکی خۆیاندا دابمەزرێن.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ckb-001-shalink', 28, 5, 6, 367, 11,
 0, now() - interval '14 days', now() - interval '14 days'),

('c0000001-0000-0000-0000-000000000007',
 'a0000001-0000-0000-0000-000000000005',
 'مێژووی مرۆڤایەتی دەیانە ئەوەی کە خوێندن و زانست لە ئیسلامدا شوێنێکی گرنگیان هەبووە. قورئانی پیرۆز بە دەستپێکردنی "ئیقرە" (بخوێنە) سەرنجی مرۆڤایەتی ڕاکێشا بۆ ئەوەی زانستی فێربن. سەردەمی زەرین ئیسلامی لە نێوان سەدەی هەشتەم و دوازدەمدا ئەو سەردەمە بوو کە موسڵمانەکان کتێبەکانی یۆنانی وەرگێڕاندن و خۆیشیان زانست پێشخستن لە ماتماتیک، ئاستەربیری، مەزناسی و پزیشکییدا.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ckb-002-shalink', 19, 3, 4, 298, 8,
 0, now() - interval '10 days', now() - interval '10 days'),

('c0000001-0000-0000-0000-000000000008',
 'a0000001-0000-0000-0000-000000000004',
 'پرسیاری گرنگ بۆ لێکۆڵینەوە: چۆن مرۆڤانی کوردستان خۆیان بەرهەم هێنانە بەرامبەر بەرکەوتنی ئایینی ئیسلامدا لە سەدەی حەوتەم و هەشتەمدا؟ دیمەنی مێژوویی نیشان دەدات کە کوردەکان بە خێرایی بوونە بەشی گرنگی شاریستانیی ئیسلامی و توێژەران و دانیشتوانی زانکۆکانی بەغداو باسرە بوون. ئایا کەسێکی هەیە تواناکانی مێژووی ئیسلامی کوردستان هەیە و بتوانین کاریکەمان بکەین؟',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ckb-003-shalink', 15, 4, 2, 201, 5,
 0, now() - interval '7 days', now() - interval '7 days'),

('c0000001-0000-0000-0000-000000000009',
 'a0000001-0000-0000-0000-000000000006',
 'بە زمانی ئینگلیزی خوێندم کە لە ئیسلامدا گوتراوە "داواکاری زانستی فەریزەیە بۆ هەموو موسڵمانێک." وا دەزانم ئەم گوتارە بە کوردی زۆر گرنگتر دەخوێنێتەوە چونکە دەنووسێنێتەوە کە زانستی فێربوون تەنیا بۆ تاکە کۆمەڵگایەک نییە بەلکو بۆ هەمووتان بە هاوبەشی. لە مالی ئێمەدا ئەمرۆ دەربارەی ئەمەدا قسەمان کرد.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ckb-004-shalink', 11, 2, 1, 89, 2,
 0, now() - interval '4 days', now() - interval '4 days'),

('c0000001-0000-0000-0000-000000000010',
 'a0000001-0000-0000-0000-000000000005',
 'تیۆری ئیجماع (یەکپارچەبوونی راوردووی زانایان) یەکێکە لە بنەما مەزهەبییەکانی فیقهی ئیسلامی. کاتێک زانایان لە سەر مەسەلەیەک یەکیان بکەوێت، ئەو یەکچوونەوەیە بەرهەمی حوکمێکی پابەند دەبێت. ئەمە ئامێرێکی مەزنە بۆ پارێزگاری لە کۆمەڵگا بە شێوەیەکی بیروبۆچوونی پزیشکی. بەڵام مەسەلەی هەستیار ئەمەیە: ئایا ئیجماعی کامل هەرگیز گوێرا دەگات؟ زانایانی فیقه لەم بارەوە ئامۆژگاری جیاوازیان هەیە.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ckb-005-shalink', 22, 6, 3, 289, 9,
 0, now() - interval '2 days', now() - interval '2 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  POSTS — Arabic
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO posts (id, author_id, text_content, post_type, status, visibility,
    share_link, reaction_count, comment_count, share_count, view_count, save_count,
    version, created_at, updated_at)
VALUES
('c0000001-0000-0000-0000-000000000011',
 'a0000001-0000-0000-0000-000000000007',
 'مسألة تطبيق الأحكام الشرعية في الواقع المعاصر من أدق المسائل التي تشغل بال الفقهاء المعاصرين. إن مقاصد الشريعة الإسلامية تدعونا إلى النظر في روح النص لا في حرفيته فقط. الإمام الشاطبي رحمه الله قال في موافقاته: إن الشريعة جاءت لمصالح العباد في العاجل والآجل. وهذا يعني أن كل حكم شرعي يجب أن يُفهم في ضوء المصلحة التي يراد تحقيقها. الفقه الإسلامي ليس جامداً بل هو نظام حي يتجدد بتجدد الحياة.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ar-001-shalink', 45, 9, 12, 621, 18,
 0, now() - interval '20 days', now() - interval '20 days'),

('c0000001-0000-0000-0000-000000000012',
 'a0000001-0000-0000-0000-000000000008',
 'الحديث النبوي الشريف مصدر تشريعي لا يقل أهمية عن القرآن الكريم. ولفهم السنة النبوية فهماً صحيحاً، لا بد من الرجوع إلى علم مصطلح الحديث الذي وضع الأئمة أسسه المتينة. فعلم الجرح والتعديل وعلم الإسناد من أدق العلوم التي طورها المسلمون تاريخياً للتحقق من صحة الروايات. هذه المنهجية العلمية في نقد الروايات تعد من أعظم الإسهامات المعرفية في تاريخ البشرية.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ar-002-shalink', 38, 7, 8, 512, 15,
 0, now() - interval '16 days', now() - interval '16 days'),

('c0000001-0000-0000-0000-000000000013',
 'a0000001-0000-0000-0000-000000000007',
 'العدل والإنصاف من أسمى القيم التي جاء بها الإسلام. قال تعالى: "إن الله يأمر بالعدل والإحسان وإيتاء ذي القربى وينهى عن الفحشاء والمنكر والبغي يعظكم لعلكم تذكرون." هذه الآية تجمع بين العدل والإحسان، وهذا الجمع يدل على أن العدل وحده لا يكفي بل يجب أن يرافقه الإحسان والتفضل. المجتمعات التي تطبق هذين المبدأين معاً هي المجتمعات الناجحة حقاً.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ar-003-shalink', 52, 11, 15, 734, 22,
 0, now() - interval '11 days', now() - interval '11 days'),

('c0000001-0000-0000-0000-000000000014',
 'a0000001-0000-0000-0000-000000000010',
 'المذهب الحنفي هو أوسع المذاهب الفقهية الإسلامية انتشاراً في العالم من حيث عدد المتبعين. أسسه الإمام أبو حنيفة النعمان رحمه الله في القرن الثاني الهجري بالكوفة. من مميزاته الاعتماد على القياس والرأي في المسائل التي لا نص فيها، مما أعطاه مرونة كبيرة في التعامل مع المستجدات. اليوم يشهد العالم اهتماماً متزايداً بدراسة الفقه المقارن بين المذاهب، وهو مجال غني بالثمار.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ar-004-shalink', 33, 6, 5, 423, 13,
 0, now() - interval '6 days', now() - interval '6 days'),

('c0000001-0000-0000-0000-000000000015',
 'a0000001-0000-0000-0000-000000000009',
 'سؤال يشغلني منذ فترة: ما هو الفرق بين الخشوع في الصلاة وبين التركيز؟ قرأت أن الخشوع هو حضور القلب مع الله وليس فقط التركيز الذهني. أي أن القلب يكون مشغولاً بمعاني ما يُتلى لا مجرد عدم التشتت. لو كان عندكم تفسير أو مرجع يشرح هذه المسألة بالتفصيل أتمنى أن تشاركوني إياه.',
 'TEXT','PUBLISHED','PUBLIC',
 'post-ar-005-shalink', 16, 8, 2, 198, 6,
 0, now() - interval '1 days', now() - interval '1 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  POST REACTIONS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO post_reactions (post_id, user_id, reaction_type, created_at, updated_at) VALUES
-- English posts
('c0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '15 days',now()-interval '15 days'),
('c0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '14 days',now()-interval '14 days'),
('c0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '14 days',now()-interval '14 days'),
('c0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '13 days',now()-interval '13 days'),

('c0000001-0000-0000-0000-000000000002','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '12 days',now()-interval '12 days'),
('c0000001-0000-0000-0000-000000000002','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '11 days',now()-interval '11 days'),
('c0000001-0000-0000-0000-000000000002','a0000001-0000-0000-0000-000000000008','LIKE',now()-interval '11 days',now()-interval '11 days'),

('c0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '8 days',now()-interval '8 days'),
('c0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000006','LIKE',now()-interval '7 days',now()-interval '7 days'),
('c0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '7 days',now()-interval '7 days'),
('c0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000010','LIKE',now()-interval '7 days',now()-interval '7 days'),

-- Kurdish posts
('c0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '14 days',now()-interval '14 days'),
('c0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '13 days',now()-interval '13 days'),
('c0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '13 days',now()-interval '13 days'),

('c0000001-0000-0000-0000-000000000010','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '2 days',now()-interval '2 days'),
('c0000001-0000-0000-0000-000000000010','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '2 days',now()-interval '2 days'),
('c0000001-0000-0000-0000-000000000010','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '2 days',now()-interval '2 days'),

-- Arabic posts
('c0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '20 days',now()-interval '20 days'),
('c0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '19 days',now()-interval '19 days'),
('c0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000008','LIKE',now()-interval '19 days',now()-interval '19 days'),
('c0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000010','LIKE',now()-interval '18 days',now()-interval '18 days'),

('c0000001-0000-0000-0000-000000000013','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '11 days',now()-interval '11 days'),
('c0000001-0000-0000-0000-000000000013','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '11 days',now()-interval '11 days'),
('c0000001-0000-0000-0000-000000000013','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '10 days',now()-interval '10 days'),
('c0000001-0000-0000-0000-000000000013','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '10 days',now()-interval '10 days')
ON CONFLICT (post_id, user_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  POST COMMENTS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO post_comments (id, post_id, author_id, text_content, reaction_count, reply_count,
    is_deleted, edited, created_at, updated_at)
VALUES
-- English post 1 comments
('d0000001-0000-0000-0000-000000000001','c0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000007',
 'Excellent analysis! Ibn Ashur''s expansion of Maqasid is indeed revolutionary. His work Al-Maqasid al-Shari''a was a game-changer for contemporary Islamic legal theory.',
 3,1,false,false, now()-interval '14 days', now()-interval '14 days'),

('d0000001-0000-0000-0000-000000000002','c0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000002',
 'Would you say there is tension between the classical five objectives and Ibn Ashur''s additions? Some scholars argue that justice and freedom are implicitly contained in the original five.',
 2,1,false,false, now()-interval '13 days', now()-interval '13 days'),

('d0000001-0000-0000-0000-000000000003','c0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000004',
 'زۆر باشە، بەرهەمی ئیبن عاشور بە کوردی وەرگێڕاوە؟ دەمەوێت زیاتر بخوێنمەوە.',
 1,0,false,false, now()-interval '12 days', now()-interval '12 days'),

-- English post 3 comments
('d0000001-0000-0000-0000-000000000004','c0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000008',
 'This hadith reminds me of how the Prophet ﷺ used to help in household chores despite being the leader of the community. True excellence starts at home.',
 4,0,false,false, now()-interval '7 days', now()-interval '7 days'),

('d0000001-0000-0000-0000-000000000005','c0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000010',
 'الأسرة هي اللبنة الأساسية للمجتمع. من أصلح أسرته أصلح مجتمعه. جزاك الله خيراً على هذا التذكير.',
 5,0,false,false, now()-interval '7 days', now()-interval '7 days'),

('d0000001-0000-0000-0000-000000000006','c0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000003',
 'This is something I needed to hear today. Thank you for sharing.',
 2,0,false,false, now()-interval '6 days', now()-interval '6 days'),

-- Arabic post 3 comments
('d0000001-0000-0000-0000-000000000007','c0000001-0000-0000-0000-000000000013',
 'a0000001-0000-0000-0000-000000000008',
 'آية عظيمة. والجمع بين العدل والإحسان يدل على أن الشريعة لا تكتفي بتطبيق القانون بل تطلب ما هو أعلى منه وهو الإحسان.',
 6,1,false,false, now()-interval '10 days', now()-interval '10 days'),

('d0000001-0000-0000-0000-000000000008','c0000001-0000-0000-0000-000000000013',
 'a0000001-0000-0000-0000-000000000009',
 'شيخنا الفاضل، ما رأيكم في تطبيق هذا المبدأ في نظام القضاء الحديث؟',
 3,1,false,false, now()-interval '10 days', now()-interval '10 days'),

('d0000001-0000-0000-0000-000000000009','c0000001-0000-0000-0000-000000000013',
 'a0000001-0000-0000-0000-000000000004',
 'مێژووی ئیسلامی پر بە نموونەی تایبەتی یەکپارچەکردنی عەدالە و ئیحساندا.',
 2,0,false,false, now()-interval '9 days', now()-interval '9 days'),

-- Kurdish post 1 comments
('d0000001-0000-0000-0000-000000000010','c0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000001',
 'Brilliantly put. The concept of Ijtihad is indeed the engine of Islamic legal dynamism. Without it, the Fiqh would become rigid and disconnected from lived reality.',
 4,0,false,false, now()-interval '13 days', now()-interval '13 days'),

('d0000001-0000-0000-0000-000000000011','c0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000005',
 'هاوکاری دەکەم. مامۆستا، بەبێ ئیجتیهاد فیقه دەبێت چیزێکی مردوو. پرسیارم ئەمەیە: کێ دەتوانێت ئیجتیهاد بکات؟ مەرجەکانی چین؟',
 3,0,false,false, now()-interval '13 days', now()-interval '13 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  RESEARCH PAPERS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO researches (
    id, researcher_id, title, slug, description, abstract_text,
    irc_sequence_number, irc_id, doi,
    status, visibility, keywords,
    reaction_count, comment_count, view_count, download_count, save_count, share_count, citation_count,
    comments_enabled, downloads_enabled, share_token, version,
    published_at, created_at, updated_at
) VALUES
-- English research
('e0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000001',
 'Maqasid al-Shariah and Contemporary Human Rights Discourse: Points of Convergence and Divergence',
 'maqasid-shariah-human-rights-2024',
 'This paper examines the relationship between the classical Islamic theory of Maqasid al-Shariah and modern international human rights law, identifying areas of convergence and productive tension.',
 'The objectives of Islamic law (Maqasid al-Shariah) as theorized by al-Ghazali, al-Shatibi, and Ibn Ashur share remarkable structural similarities with modern human rights frameworks. Both systems seek to protect fundamental human interests — life, dignity, freedom, property, and family. This paper argues that far from being antithetical, these two traditions can engage in productive dialogue. By examining specific rights instruments including the UDHR and ICCPR alongside classical Maqasid literature, we identify areas of convergence in substance while acknowledging methodological differences. The paper concludes that a Maqasid-based approach to Islamic jurisprudence can serve as a bridge between Islamic legal tradition and universal human rights norms.',
 1, 'IRC-2024-000001', '10.12345/irc.2024.000001',
 'PUBLISHED','PUBLIC', 'maqasid, human rights, Islamic law, al-Shatibi, jurisprudence',
 34, 8, 892, 156, 29, 12, 6,
 true, true, 'sharetkn001', 0,
 now()-interval '60 days', now()-interval '65 days', now()-interval '60 days'),

('e0000001-0000-0000-0000-000000000002',
 'a0000001-0000-0000-0000-000000000002',
 'Female Scholarship in Early Islamic Civilization: Methodology and Legacy',
 'female-scholarship-early-islam-2024',
 'An examination of the institutional and methodological contributions of female scholars in the formative period of Islamic intellectual history, with particular focus on the transmission of Prophetic narrations.',
 'Islamic civilization produced an remarkable tradition of female scholarship that has been understudied in both Western academia and contemporary Muslim communities. This paper documents and analyzes the contributions of female scholars (alimat) from the first to the eighth Islamic centuries. Using primary sources including biographical dictionaries (tabaqat), hadith chain analyses (isnads), and legal compendia, we reconstruct the intellectual networks through which female scholarship operated. Key figures examined include Aisha bint Abi Bakr, Umm al-Darda, Karima al-Marwaziyya, and Fatima bint Ibrahim. The paper argues that their contributions were not peripheral but central to the preservation and development of Islamic knowledge, and their methodologies continue to have relevance for contemporary Islamic scholarship.',
 2, 'IRC-2024-000002', '10.12345/irc.2024.000002',
 'PUBLISHED','PUBLIC', 'female scholars, hadith, Islamic history, alimat, tabaqat',
 28, 5, 634, 98, 21, 8, 4,
 true, true, 'sharetkn002', 0,
 now()-interval '45 days', now()-interval '50 days', now()-interval '45 days'),

-- Kurdish research
('e0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000004',
 'ئیجتیهاد لە فیقهی ئیسلامی: ئامرازەکانەکانی و مەرجەکان',
 'ijtihad-fiqh-islami-ckb-2024',
 'لێکۆڵینەوەیەکی تەواو لە بارەی شەرتەکان و ئامێرەکانی ئیجتیهاد لە فیقهی ئیسلامی، لەگەڵ پێشکەش کردنی ئامۆژگاریەکانی زانایانی کلاسیک و هاوچەرخ.',
 'ئیجتیهاد هی یاسای ئیسلامی بوونی بە خودوهازری تایبەتمەند دەکات. ئەم توێژینەوەیە دوای گوتە کلاسیکییەکانی ئیمام شافیعی، ئیبن حەزم و ئیمام قرافی کۆ دەکاتەوە بۆ ئەوەی دیمەنێکی تەواو لە شەرتی ئیجتیهاد پێشکەش بکات. هەروەها توێژینەوەکە بارودۆخی ئیجتیهاد لە سەردەمی هاوچەرخدا شیدەکاتەوە، بەتایبەتی لە پرسیارە نوێیەکانی بیۆئیتیک، فینانسی ئیسلامی، و یاسای دیجیتاڵ.',
 3, 'IRC-2024-000003', '10.12345/irc.2024.000003',
 'PUBLISHED','PUBLIC', 'ئیجتیهاد, فیقه, یاسای ئیسلامی, مەزهەب',
 19, 4, 445, 67, 15, 6, 3,
 true, true, 'sharetkn003', 0,
 now()-interval '30 days', now()-interval '35 days', now()-interval '30 days'),

('e0000001-0000-0000-0000-000000000004',
 'a0000001-0000-0000-0000-000000000005',
 'مێژووی ئیسلامی کوردستان: توێژینەوەیەک لە سەردەمی عەباسیدا',
 'mhzwy-islami-kurdistan-abbasid-2024',
 'توێژینەوەیەکی مێژووییە دەربارەی بەشداریی کوردەکان لە شاریستانیی ئیسلامی لە سەردەمی خەلیفەی عەباسیدا، بە گریمانەی لەسەر توێژەر و زانا و کارگێڕانی کورد.',
 'لە سەردەمی خەلیفەی عەباسیدا (٧٥٠-١٢٥٨ز) کوردەکانی ئەم ناوچەیە بەشداریی گرنگیان لە زانست و یاسا و مامەڵەی ئیسلامی کرد. ئەم توێژینەوەیە کاتی توان لە ناساندنی هێمای مێژووییی کورد لە ئەو سەردەمەدا دەکات، بە پشتبەستن بە سەرچاوە کلاسیکی عەرەبی و زانکۆیانەی نوێ. دەربکەین کە چۆن زانایانی کورد لە بەغداو باسرە و کوفەدا کارمان کردووە و بەرهەمیان داوە.',
 4, 'IRC-2024-000004', '10.12345/irc.2024.000004',
 'PUBLISHED','PUBLIC', 'کوردستان, مێژووی ئیسلامی, عەباسی, زانست',
 14, 3, 312, 45, 11, 4, 2,
 true, true, 'sharetkn004', 0,
 now()-interval '20 days', now()-interval '25 days', now()-interval '20 days'),

-- Arabic research
('e0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000007',
 'الاجتهاد الفقهي المعاصر: إشكاليات المنهج وآفاق التجديد',
 'ijtihad-fiqhi-muasir-ar-2024',
 'دراسة تحليلية نقدية لواقع الاجتهاد الفقهي في العصر الحديث، مع استعراض الإشكاليات المنهجية التي تعيق التجديد الفقهي وتقديم مقترحات للخروج من المأزق.',
 'يعيش الفقه الإسلامي المعاصر أزمة منهجية حقيقية تتجلى في ثلاثة مستويات: أولاً، الفجوة بين النص الشرعي والواقع المعاصر المتغير. ثانياً، ضعف الكفاءة التأهيلية للمجتهدين المعاصرين في العلوم الإنسانية والطبيعية. ثالثاً، غياب المنظومة المؤسسية التي تنتج الاجتهاد الجماعي وتضمن جودته. يقترح البحث نموذجاً للاجتهاد المعاصر يقوم على التكامل المنهجي بين العلوم الشرعية والعلوم الإنسانية، وتفعيل آليات الاجتهاد الجماعي المؤسسي، والتمييز الدقيق بين ثوابت الشريعة ومتغيراتها.',
 5, 'IRC-2024-000005', '10.12345/irc.2024.000005',
 'PUBLISHED','PUBLIC', 'اجتهاد, فقه معاصر, تجديد, مقاصد الشريعة',
 47, 11, 1234, 278, 38, 21, 9,
 true, true, 'sharetkn005', 0,
 now()-interval '90 days', now()-interval '95 days', now()-interval '90 days'),

('e0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000010',
 'المذهب الحنفي ومسائل الحداثة: دراسة تطبيقية في الفقه المقارن',
 'madhab-hanafi-hadatha-ar-2024',
 'دراسة في كيفية تعامل الفقه الحنفي مع المستجدات المعاصرة في مجالات المعاملات المالية الرقمية والأحوال الشخصية وأخلاقيات الطب الحديث.',
 'يتميز المذهب الحنفي بمنهجيته في التعامل مع المستجدات الفقهية القائمة على توسيع دائرة القياس والاستحسان. تبحث هذه الدراسة في مدى صلاحية هذا المنهج لمواجهة تحديات العصر في ثلاثة مجالات: أولاً، العملات الرقمية والمعاملات المالية الإلكترونية. ثانياً، مستجدات الأحوال الشخصية كالتلقيح الاصطناعي والتبني. ثالثاً، أخلاقيات الطب الحديث كالقتل الرحيم والاستنساخ. يخلص البحث إلى أن المنهج الحنفي يملك من المرونة ما يؤهله للتعامل مع هذه المستجدات بشرط تحديث أدواته المعرفية.',
 6, 'IRC-2024-000006', '10.12345/irc.2024.000006',
 'PUBLISHED','PUBLIC', 'المذهب الحنفي, فقه معاصر, معاملات رقمية, أخلاقيات طبية',
 39, 7, 876, 195, 31, 14, 7,
 true, true, 'sharetkn006', 0,
 now()-interval '40 days', now()-interval '45 days', now()-interval '40 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  RESEARCH REACTIONS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO research_reactions (research_id, user_id, reaction_type, created_at, updated_at) VALUES
('e0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '59 days',now()-interval '59 days'),
('e0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '58 days',now()-interval '58 days'),
('e0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '57 days',now()-interval '57 days'),
('e0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000008','LIKE',now()-interval '56 days',now()-interval '56 days'),
('e0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000010','LIKE',now()-interval '55 days',now()-interval '55 days'),

('e0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '89 days',now()-interval '89 days'),
('e0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '88 days',now()-interval '88 days'),
('e0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '88 days',now()-interval '88 days'),
('e0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000008','LIKE',now()-interval '87 days',now()-interval '87 days'),
('e0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000010','LIKE',now()-interval '86 days',now()-interval '86 days'),
('e0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '85 days',now()-interval '85 days'),

('e0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '29 days',now()-interval '29 days'),
('e0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000006','LIKE',now()-interval '28 days',now()-interval '28 days'),
('e0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '28 days',now()-interval '28 days'),

('e0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '39 days',now()-interval '39 days'),
('e0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '38 days',now()-interval '38 days'),
('e0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000008','LIKE',now()-interval '37 days',now()-interval '37 days'),
('e0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '37 days',now()-interval '37 days')
ON CONFLICT (research_id, user_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  RESEARCH COMMENTS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO research_comments (id, research_id, user_id, content, like_count, reply_count,
    is_edited, is_hidden, deleted_at, created_at, updated_at)
VALUES
-- Research 1 comments (EN)
('f0000001-0000-0000-0000-000000000001',
 'e0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000007',
 'This paper offers a compelling synthesis. The comparison between Maqasid and UDHR is particularly insightful. One question: how do you handle the apparent tension between Islamic teachings on religious freedom and Article 18 of the UDHR?',
 7,1,false,false,null, now()-interval '58 days', now()-interval '58 days'),

('f0000001-0000-0000-0000-000000000002',
 'e0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000002',
 'Excellent research. The methodological framework proposed here could be a significant contribution to the field of comparative legal theory. I would suggest looking at Jasser Auda''s work on Maqasid as a systems theory for complementary insights.',
 5,0,false,false,null, now()-interval '57 days', now()-interval '57 days'),

('f0000001-0000-0000-0000-000000000003',
 'e0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000010',
 'ورقة علمية ممتازة. التقارب بين مقاصد الشريعة وحقوق الإنسان موضوع يحتاج مزيداً من الدراسة والبحث. نتطلع لقراءة أعمالكم القادمة في هذا الموضوع.',
 4,0,false,false,null, now()-interval '55 days', now()-interval '55 days'),

-- Research 5 comments (AR)
('f0000001-0000-0000-0000-000000000004',
 'e0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000001',
 'Groundbreaking research. The three-level framework for contemporary Ijtihad is elegantly constructed. The gap between classical training and modern sciences is indeed the core challenge facing Islamic jurisprudence today.',
 9,1,false,false,null, now()-interval '87 days', now()-interval '87 days'),

('f0000001-0000-0000-0000-000000000005',
 'e0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000010',
 'بارك الله في جهدكم يا دكتور. النموذج الثلاثي المقترح لتجديد الاجتهاد يمثل إضافة حقيقية للمكتبة الفقهية المعاصرة. هل فكرتم في إنشاء مركز بحثي متخصص لتطبيق هذا النموذج؟',
 8,0,false,false,null, now()-interval '86 days', now()-interval '86 days'),

('f0000001-0000-0000-0000-000000000006',
 'e0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000004',
 'توێژینەوەیەکی زۆر گرنگ. پرسیار لە نموونەکانی ئیجتیهادی کۆمەڵی دەکەم - ئایا شێوەی کاری مەجمەع فیقهیی ئیسلامی لە جەدا نموونەی باشییە بۆ ئیجتیهادی کۆمەڵی پابەند؟',
 6,0,false,false,null, now()-interval '85 days', now()-interval '85 days'),

-- Research 3 comments (CKB)
('f0000001-0000-0000-0000-000000000007',
 'e0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000001',
 'Superb contribution to Kurdish Islamic scholarship. The systematization of Ijtihad conditions is valuable. One additional consideration: the role of linguistic competency in Arabic as a prerequisite — how should this be addressed for Sorani-speaking scholars?',
 5,0,false,false,null, now()-interval '28 days', now()-interval '28 days'),

('f0000001-0000-0000-0000-000000000008',
 'e0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000006',
 'زۆر سوپاس بۆ ئەم توێژینەوەی بنچینەیی. وەک خوێندکاری کوردی توێژینەوەکان بە کوردی زۆر کەمیان بووە و ئەمە بوونی گرنگ دەکات.',
 4,0,false,false,null, now()-interval '27 days', now()-interval '27 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  QnA QUESTIONS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO questions (id, author_id, title, body, status, answer_count,
    view_count, save_count, share_count, answers_locked, created_at, updated_at)
VALUES
-- English questions
('g0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000003',
 'What is the Islamic ruling on cryptocurrency investment from a Sharia perspective?',
 'I have been researching cryptocurrency as an investment option. There seem to be differing scholarly opinions on whether Bitcoin and similar assets are permissible (halal) under Islamic law. The main questions I have are: 1) Is the underlying technology (blockchain) permissible? 2) Is the speculative nature of crypto similar to gambling (maysir) which is prohibited? 3) Do any major fiqh councils have a ruling on this? I would appreciate responses that cite specific scholarly opinions or fatwa councils.',
 'OPEN', 3, 892, 34, 12, false, now()-interval '25 days', now()-interval '25 days'),

('g0000001-0000-0000-0000-000000000002',
 'a0000001-0000-0000-0000-000000000002',
 'How did early Muslim scholars approach the preservation and authentication of Hadith? What methodologies did they develop?',
 'I am writing a paper on the hadith sciences and want to understand the historical development of Isnad (chain of transmission) criticism. Specifically: 1) When did systematic Hadith collection begin? 2) What criteria did scholars like Imam Bukhari use to evaluate narrators? 3) How does the concept of Mutawatir vs. Ahad hadith affect legal rulings? Looking for detailed scholarly answers with references to classical works.',
 'ANSWERED', 4, 1234, 56, 18, false, now()-interval '40 days', now()-interval '40 days'),

-- Kurdish questions
('g0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000006',
 'ئایا لە ئیسلامدا دادگایی کۆمەڵایەتی مەشروعیەتی هەیە؟',
 'ببورە من خوێندکارم و لە دەرسی فیقهی ئیسلامیدا بوم. مامۆستاکەم دەیگوت دادگایی کۆمەڵایەتی (Social justice) بنچینەیەکی گرنگی ئیسلامییە. بەڵام خەڵکانی دیکە دەیانگوین کە ئیسلام تەنیا بەر بە دادگایی کەسایەتییە نە کۆمەڵایەتی. دەمەوێت بزانم ئایا قورئان و سوننەت بەرپرسایەتی کۆمەڵایەتی دادگایی لە سەر دەوڵەت دەخاتە بەردەم؟',
 'OPEN', 2, 234, 12, 4, false, now()-interval '15 days', now()-interval '15 days'),

('g0000001-0000-0000-0000-000000000004',
 'a0000001-0000-0000-0000-000000000005',
 'چۆن کتێبەکانی کلاسیک بە کوردی وەرگێڕدراون؟ سەرچاوەکان چین؟',
 'من توێژینەوەیەکم دەبێت لەسەر وەرگێڕانی مەتنی کلاسیکی ئیسلامی بۆ زمانی کوردی. دەزانم کە بەشێکی زۆر لە بنچینەکانی فیقه و عەقیدە بە کوردی وەرگێڕدراون، بەڵام نامەزانم کە ئایا کۆی بەرهەمی وەرگێڕاوی ئیسلامی بە کوردی هەیە؟ ئایا دامەزراوەیەک هەیە کە ئەم کارە دەکات؟',
 'OPEN', 2, 178, 8, 3, false, now()-interval '10 days', now()-interval '10 days'),

-- Arabic questions
('g0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000009',
 'ما حكم الإسلام في الاستثمار في الأسهم والبورصة؟',
 'أريد أن أستثمر في سوق الأسهم لكنني متردد لأنني لست متأكداً من الحكم الشرعي. سمعت آراء متضاربة: بعض العلماء يقول إنه جائز بشروط وبعضهم يقول إنه محرم لأنه يتضمن الغرر. هل يمكن أن تشرحوا لي: ١) ما الفرق بين الاستثمار في الأسهم والمضاربة المحرمة؟ ٢) ما الشروط التي يجب توفرها لتكون المعاملة مشروعة؟ ٣) هل للمجامع الفقهية الكبرى موقف محدد من هذه المسألة؟',
 'ANSWERED', 3, 1456, 67, 23, false, now()-interval '50 days', now()-interval '50 days'),

('g0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000008',
 'ما المقصود بالاجتهاد الجماعي وكيف يختلف عن الاجتهاد الفردي؟',
 'في دراسة الفقه المعاصر وجدت مصطلح "الاجتهاد الجماعي" يتكرر كثيراً، لكنني لم أجد تعريفاً واضحاً ودقيقاً له. هل يمكن لأحد العلماء أن يشرح: ١) ما التعريف الدقيق للاجتهاد الجماعي؟ ٢) ما الفرق بينه وبين الإجماع التقليدي؟ ٣) هل المجامع الفقهية الحديثة مثل رابطة العالم الإسلامي تحقق هذا النوع من الاجتهاد؟ ٤) ما حجيته وملزميته؟',
 'OPEN', 3, 678, 34, 11, false, now()-interval '20 days', now()-interval '20 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  QnA ANSWERS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO question_answers (id, question_id, author_id, body, reaction_count,
    reply_count, is_accepted, best_answer_vote_count, is_edited, created_at, updated_at)
VALUES
-- Answers for Q1 (crypto)
('h0000001-0000-0000-0000-000000000001',
 'g0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000001',
 'This is an excellent question that many contemporary scholars are grappling with. The scholarly opinions fall into three broad camps: 1) PROHIBITED: Scholars like Mufti Taqi Usmani initially argued cryptocurrencies are impermissible due to excessive speculation (gharar) and lack of intrinsic value. 2) PERMISSIBLE WITH CONDITIONS: Many scholars argue crypto is permissible if used as a medium of exchange (like fiat currency) but impermissible for pure speculation. 3) NEUTRAL TOOL: The blockchain technology itself is permissible — it is merely a ledger. The permissibility depends on the specific use case. The Islamic Fiqh Academy (Mecca) has discussed this and the majority position trending toward permissibility for stablecoins and utility tokens while cautioning against highly speculative trading. Key references: Mufti Faraz Adam''s research at Amanah Finance, and the AAOIFI standards on digital assets.',
 18,2,true,45, false, now()-interval '24 days', now()-interval '24 days'),

('h0000001-0000-0000-0000-000000000002',
 'g0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000007',
 'أضيف على ما قاله الأخ الكريم: مجمع الفقه الإسلامي الدولي التابع لمنظمة التعاون الإسلامي قد ناقش هذه المسألة في دوراته الأخيرة. الأرجح أن العملات الرقمية من حيث طبيعتها القانونية ليست نقوداً بالمفهوم الفقهي الكلاسيكي، لذلك لا يُطبق عليها ربا الفضل. لكن الاتجار فيها قد يدخل في الغرر الفاحش إذا كان مجرد مضاربة. الضابط الشرعي: إذا كان المقصود الاستثمار في التقنية أو استخدام العملة وسيطاً للمعاملات فالأصل الإباحة، وإن كان المقصود المضاربة على تقلبات الأسعار فهذا مظنة الحرمة.',
 12,1,false,28, false, now()-interval '23 days', now()-interval '23 days'),

('h0000001-0000-0000-0000-000000000003',
 'g0000001-0000-0000-0000-000000000001',
 'a0000001-0000-0000-0000-000000000004',
 'لەم بارەوەش پرسیارێکی گرنگم هەیە: کاتێک دەوڵەتەکان خۆیان دەستیان دەکات بە دەرکردنی دراوی دیجیتاڵ (CBDC)، ئایا ئەوەتر مەسەلەی ئیسلامی وەڵام دراوەکەی ئاسانتر دەبێت؟ CBDC دراوێکی دیجیتاڵی بنچینەداری دەوڵەتییە نە ئازادانە. زانایان دیدگایانی جیاوازیان هەیە. پێشنیارم ئەوەیە کە ئێوە پرسیار لە مامۆستایەکی متمانەپێکراو بکەن لە ئێوەی تایبەت چونکە ئەم مەسەلەیە نوێیە و بۆچوونی شەخصی زانا مهمە.',
 8,0,false,14, false, now()-interval '22 days', now()-interval '22 days'),

-- Answers for Q2 (hadith sciences)
('h0000001-0000-0000-0000-000000000004',
 'g0000001-0000-0000-0000-000000000002',
 'a0000001-0000-0000-0000-000000000001',
 'The development of Hadith sciences represents one of the most sophisticated intellectual achievements in human history. Here is a concise overview: TIMELINE: The first systematic efforts began in the late first century AH under Caliph Umar ibn Abd al-Aziz who commissioned Abu Bakr ibn Hazm to collect narrations. The golden age of Hadith collection was the second and third centuries AH. IMAM BUKHARI''S METHODOLOGY: Al-Bukhari applied remarkably rigorous standards: (1) Personal meeting (liqa) between narrator and teacher — not just contemporaneity; (2) Memory and reliability verified through cross-examination; (3) No narrator who lied even once about non-hadith matters. His Sahih contains approximately 7,275 hadiths (with repetitions) out of 600,000 he examined. MUTAWATIR vs. AHAD: Mutawatir (mass-transmitted) narrations cannot be rejected — they produce certainty (yaqin). Ahad (individual-chain) narrations produce probability (zann) and differ in their legal implications: mandatory rulings typically require stronger transmission.',
 24,2,true,67, false, now()-interval '39 days', now()-interval '39 days'),

('h0000001-0000-0000-0000-000000000005',
 'g0000001-0000-0000-0000-000000000002',
 'a0000001-0000-0000-0000-000000000008',
 'أضيف على الإجابة السابقة: علم الرجال (نقد الرواة) هو الركيزة الأساسية في علوم الحديث. ووضع العلماء تدريجاً مصطلحات دقيقة لتقييم الرواة من أعلاها: الثقة، والصدوق، وإلى أدناها: المتروك، والمكذوب عليه. ومن الكتب الأساسية في هذا العلم: تهذيب الكمال للمزي، وميزان الاعتدال للذهبي. المنهج النقدي الإسلامي في تمحيص الروايات يفوق بمراحل ما وصل إليه الغرب في نقد النصوص التاريخية حتى القرن التاسع عشر.',
 18,1,false,42, false, now()-interval '38 days', now()-interval '38 days'),

-- Answers for Q3 (Kurdish social justice)
('h0000001-0000-0000-0000-000000000006',
 'g0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000004',
 'پرسیارێکی زۆر گرنگت کردووە. بەڵێ، دادگایی کۆمەڵایەتی بنچینەیەکی ئیسلامیی پتەوە هەیە. قورئانی پیرۆز دەفەرموێ "لا تَأْكُلُوا أَمْوَالَكُم بَيْنَكُم بِالْبَاطِلِ" — مانای ئەوەیە کە پارەکانتان بە شێوەیەکی نادروست مەخۆن. ئەمە پرنسیپێکی ئابووریی دادگایانەیە. هەروەها سیستەمی زەکات کە فەریزەی ئیسلامییە ئامرازێکی داوودەکردنی دارایییە لە نێوان چینە جیاوازەکانی کۆمەڵگادا. دەوڵەتی ئیسلامی پابەندی نان دانەوەی هەژاران، پارێزگاری لە کمینەکان، و پێدانی دادگایی بۆ هەموو باژێرگایانەیە. کتێبی "ئابووریی ئیسلامی" ی شیخ یوسف قەرەداوی بەرهەمی باشە بۆ ئەم بابەتە.',
 14,1,true,32, false, now()-interval '14 days', now()-interval '14 days'),

('h0000001-0000-0000-0000-000000000007',
 'g0000001-0000-0000-0000-000000000003',
 'a0000001-0000-0000-0000-000000000001',
 'An important question. The Quran repeatedly emphasizes collective responsibilities. The concept of "Hisba" — the duty to command right and forbid wrong — is inherently a social justice mechanism. The Caliph Umar ibn al-Khattab said famously: "I will be held accountable if a dog dies of hunger on the banks of the Euphrates." This shows that Islamic governance fundamentally includes social welfare obligations. Modern Islamic scholars like Sheikh Qaradawi and Muhammad Asad have written extensively on the Islamic welfare state (Dawla Islamiyya) which is built on redistributive justice principles.',
 11,0,false,22, false, now()-interval '14 days', now()-interval '14 days'),

-- Answers for Q5 (Arabic stock market)
('h0000001-0000-0000-0000-000000000008',
 'g0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000007',
 'مسألة الاستثمار في الأسهم من المسائل المستحدثة التي بحثها العلماء المعاصرون بتوسع. وخلاصة القول أن الراجح جواز الاستثمار في الأسهم بضوابط: أولاً: أن يكون نشاط الشركة مباحاً في ذاته — لا يجوز الاستثمار في شركات البنوك الربوية أو الخمر أو القمار. ثانياً: ألا يكون الاستثمار مجرد مضاربة على الأسعار دون قصد التملك. ثالثاً: إذا تعاملت الشركة ببعض المعاملات غير المشروعة بصورة تبعية وغير أساسية جاز الاستثمار مع التخلص من نسبة الأرباح المحرمة. والمرجع في هذا قرارات مجمع الفقه الإسلامي الدولي رقم ٦٣ ومعايير هيئة المحاسبة والمراجعة للمؤسسات المالية الإسلامية (أيوفي) رقم ٢١.',
 21,2,true,58, false, now()-interval '49 days', now()-interval '49 days'),

('h0000001-0000-0000-0000-000000000009',
 'g0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000010',
 'أود أن أضيف نقطة مهمة: الفرق بين الاستثمار في الأسهم والمضاربة المحرمة يكمن في النية والمآل. المستثمر الذي يشتري سهماً ليمتلك حصة في شركة حقيقية تنتج سلعاً أو خدمات مباحة هو في الأصل شريك في مشروع حقيقي — وهذا جائز. أما من يشتري السهم لبيعه في اليوم ذاته استناداً إلى تحركات الأسعار، دون أي اهتمام بالشركة ذاتها، فهذا أقرب إلى الميسر. صياغة الضابط: هل الربح المتوقع ناتج عن عمل الشركة (إنتاج وتوزيع أرباح) أم ناتج فقط عن تقلبات سعرية؟',
 16,1,false,39, false, now()-interval '48 days', now()-interval '48 days'),

('h0000001-0000-0000-0000-000000000010',
 'g0000001-0000-0000-0000-000000000005',
 'a0000001-0000-0000-0000-000000000008',
 'من الناحية العملية: يوجد الآن فلاتر شرعية لاختيار الأسهم الجائزة، منها: مؤشر داو جونز الإسلامي، ومؤشر FTSE الإسلامي، وكثير من شركات الاستشارات الشرعية توفر قوائم بالأسهم الحلال. أنصح بمراجعة موقع Zoya أو Islamicly للتحقق من شرعية أي سهم قبل الاستثمار فيه.',
 12,0,false,27, false, now()-interval '47 days', now()-interval '47 days'),

-- Answers for Q6 (collective ijtihad)
('h0000001-0000-0000-0000-000000000011',
 'g0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000007',
 'سؤال دقيق جداً. الاجتهاد الجماعي (أو ما يسميه بعض العلماء "الاجتهاد المؤسسي") يختلف عن الإجماع الكلاسيكي في عدة نواحٍ: أولاً — التعريف: الاجتهاد الجماعي هو استنباط الحكم الشرعي من خلال هيئة متخصصة تضم علماء الشريعة والمتخصصين في العلوم المستهدفة معاً. ثانياً — الفرق عن الإجماع: الإجماع الكلاسيكي يشترط الاتفاق التام بين جميع علماء العصر، بينما الاجتهاد الجماعي يقبل الأغلبية. كذلك الإجماع ملزم قطعاً بينما الاجتهاد الجماعي يفيد الظن الراجح. ثالثاً — المجامع الفقهية الحديثة: نعم، مجمع الفقه الإسلامي التابع لمنظمة التعاون الإسلامي يمثل نموذجاً للاجتهاد الجماعي المؤسسي. رابعاً — الحجية: قراراته ملزمة أخلاقياً وليس قانونياً.',
 19,1,true,52, false, now()-interval '19 days', now()-interval '19 days'),

('h0000001-0000-0000-0000-000000000012',
 'g0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000001',
 'Collective Ijtihad is essentially the institutionalization of scholarly deliberation. The Prophet ﷺ himself practiced a form of consultative decision-making (Shura). Contemporary examples of successful collective ijtihad institutions include: 1) The International Islamic Fiqh Academy in Jeddah 2) The European Council for Fatwa and Research 3) The Fiqh Council of North America. These bodies combine classical Sharia expertise with domain knowledge in economics, medicine, and law — making them more capable of addressing contemporary issues than any single scholar. The key advantage over individual Ijtihad is error-correction through peer review and specialization.',
 15,0,false,37, false, now()-interval '18 days', now()-interval '18 days'),

('h0000001-0000-0000-0000-000000000013',
 'g0000001-0000-0000-0000-000000000006',
 'a0000001-0000-0000-0000-000000000004',
 'زیادەکردنێکی گرنگ: مامۆستای کوردی شیخ عبدالکریم مدرس رحمه الله کتێبی زۆر گرنگی نووسیووە لە بارەی ئیجتیهاد و مەرجەکانیەوە. کتێبەکەی "الفقه المقارن" سەرچاوەیەکی باشە بۆ تێگەیشتن لەم مەسەلەیە لە دیدگەی زانایانی کوردستانەوە. ئیجتیهادی کۆمەڵی دەرفەتە زۆر باش چونکە کاتێک زانایانی جیا جیا کۆ دەبنەوە، شانسی هەڵەیەکی کەمتر هەیە.',
 11,0,false,24, false, now()-interval '17 days', now()-interval '17 days')
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  ANSWER REACTIONS
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO answer_reactions (answer_id, user_id, reaction_type, created_at, updated_at) VALUES
('h0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '24 days',now()-interval '24 days'),
('h0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000008','LIKE',now()-interval '23 days',now()-interval '23 days'),
('h0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '23 days',now()-interval '23 days'),
('h0000001-0000-0000-0000-000000000001','a0000001-0000-0000-0000-000000000010','LIKE',now()-interval '22 days',now()-interval '22 days'),

('h0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '39 days',now()-interval '39 days'),
('h0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '38 days',now()-interval '38 days'),
('h0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000007','LIKE',now()-interval '38 days',now()-interval '38 days'),
('h0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '37 days',now()-interval '37 days'),

('h0000001-0000-0000-0000-000000000008','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '49 days',now()-interval '49 days'),
('h0000001-0000-0000-0000-000000000008','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '48 days',now()-interval '48 days'),
('h0000001-0000-0000-0000-000000000008','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '48 days',now()-interval '48 days'),

('h0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '19 days',now()-interval '19 days'),
('h0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000004','LIKE',now()-interval '18 days',now()-interval '18 days'),
('h0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000005','LIKE',now()-interval '18 days',now()-interval '18 days'),
('h0000001-0000-0000-0000-000000000011','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '17 days',now()-interval '17 days'),

('h0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000001','LIKE',now()-interval '14 days',now()-interval '14 days'),
('h0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000002','LIKE',now()-interval '13 days',now()-interval '13 days'),
('h0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000009','LIKE',now()-interval '13 days',now()-interval '13 days')
ON CONFLICT (answer_id, user_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
--  USER FOLLOWS (so feeds have content)
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO user_follows (follower_id, following_id, followed_at) VALUES
('a0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000001',now()-interval '55 days'),
('a0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000002',now()-interval '54 days'),
('a0000001-0000-0000-0000-000000000003','a0000001-0000-0000-0000-000000000007',now()-interval '50 days'),
('a0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000004',now()-interval '25 days'),
('a0000001-0000-0000-0000-000000000006','a0000001-0000-0000-0000-000000000005',now()-interval '24 days'),
('a0000001-0000-0000-0000-000000000009','a0000001-0000-0000-0000-000000000007',now()-interval '40 days'),
('a0000001-0000-0000-0000-000000000009','a0000001-0000-0000-0000-000000000010',now()-interval '39 days'),
('a0000001-0000-0000-0000-000000000009','a0000001-0000-0000-0000-000000000008',now()-interval '38 days'),
('a0000001-0000-0000-0000-000000000002','a0000001-0000-0000-0000-000000000001',now()-interval '110 days'),
('a0000001-0000-0000-0000-000000000002','a0000001-0000-0000-0000-000000000007',now()-interval '108 days'),
('a0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000001',now()-interval '190 days'),
('a0000001-0000-0000-0000-000000000004','a0000001-0000-0000-0000-000000000007',now()-interval '185 days'),
('a0000001-0000-0000-0000-000000000005','a0000001-0000-0000-0000-000000000004',now()-interval '85 days'),
('a0000001-0000-0000-0000-000000000008','a0000001-0000-0000-0000-000000000007',now()-interval '140 days'),
('a0000001-0000-0000-0000-000000000008','a0000001-0000-0000-0000-000000000001',now()-interval '138 days'),
('a0000001-0000-0000-0000-000000000010','a0000001-0000-0000-0000-000000000007',now()-interval '240 days'),
('a0000001-0000-0000-0000-000000000010','a0000001-0000-0000-0000-000000000001',now()-interval '238 days')
ON CONFLICT DO NOTHING;

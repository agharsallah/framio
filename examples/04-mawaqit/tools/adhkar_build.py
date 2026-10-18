"""Builds app/src/main/assets/adhkar.json from three openly licensed datasets (see ADHKAR-SOURCE.txt).

No Arabic is typed here: every Arabic string is copied from a dataset; the only transformation is splitting the
before-sleep tasbih (one dataset item "سبحان الله (ثلاثاً وثلاثين) والحمد لله (…) والله أكبر (أربعاً وثلاثين)") into its
three parts, by cutting the dataset's own string at its count annotations.

The "anytime" collections (dhikr, istighfar, salawat, quran, distress, everyday) take a dhikr out of the hadith that
carries it ("…من قال: سبحان الله وبحمده في يوم مائة مرة…") by word positions in the dataset's own string, never by
retyping it; the whole hadith becomes the virtue. The Quranic du'as are the Tanzil text itself, ayah by ayah.

usage: python3 -I tools/adhkar_build.py <seen dir> <fitrahive dir> <muslim.db> <out.json> <quran dir>
  <quran dir>: ayah_<s>_<a>.json from https://api.alquran.cloud/v1/ayah/<s>:<a>/editions/quran-simple,quran-simple-clean,en.pickthall
"""
import glob
import json
import re
import sqlite3
import sys

seen_dir, fit_dir, db_path, out_path, quran_dir = sys.argv[1:6]

TASHKEEL = re.compile('[ؐ-ًؚ-ٰٟۖ-ۭـ]')


def norm(s):
    """Arabic letters only, without vowels/marks, alef/ya/ta-marbuta unified: to match the same text across datasets."""
    s = TASHKEEL.sub('', s)
    s = re.sub('[أإآٱ]', 'ا', s).replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
    s = re.sub('[^ء-ي ]', ' ', s)
    return re.sub(' +', ' ', s).strip()


def clean(s):
    return re.sub('[ \t]+', ' ', (s or '').replace('\r', '')).strip()


BOOKS = [
    ('Al-Adab Al-Mufrad', r"Al-Bukhari,? (?:in )?(?:Sahih )?Al-'?Adab Al-Mufrad"),
    ('Al-Bukhari', r"(?:Sahih )?Al-Bukhari(?: with Al-Fath)?"), ('Muslim', r'(?:Sahih )?Muslim'),
    ('Abu Dawud', r'Abu Daw[uo]{1,2}d'), ('At-Tirmidhi', r'(?:At|Al)-Tirmidhi'), ('Ibn Majah', r'Ibn Majah'),
    ('Ahmad', r'Ahmad'), ("An-Nasa'i", r"(?:An|Al)-Nasa'i"), ('Al-Hakim', r'Al-Hakim'), ('Ibn As-Sunni', r'Ibn (?:As|Al)-Sunni'),
    ('Al-Tabarani', r'Al-Tabarani'),
]
# The number right after the book: "(No. 3667)", ", 7/150, No. 6306", ": 6404", " 2692", " 4/322", ", 11/101 [No. 6307]"
NUM = r'[,:]?\s*(?:\(No\.\s*(\d+)\)|(?:\d+\s*/\s*\d+\s*,?\s*)?\[?No\.\s*(\d+)|(\d+\s*/\s*\d+)|(\d+)\b)?'


def short_ref(src):
    """"Narrated by Al-Bukhari, 7/150, No. 6306." → "Al-Bukhari 6306": the first two books cited, with their number."""
    found = []
    taken = []
    for name, pat in BOOKS:
        for m in re.finditer(pat + NUM, src):
            if any(a <= m.start() < b for a, b in taken):
                continue
            num = next((g for g in m.groups() if g), None)
            found.append((m.start(), name, num))
            taken.append((m.start(), m.end()))
    found.sort()
    out = []
    for _, name, num in found:
        if any(o.split(' ')[0] == name.split(' ')[0] and o.startswith(name) for o in out):
            continue
        out.append(f'{name} {num.replace(" ", "")}' if num else name)
        if len(out) == 2:
            break
    return ' · '.join(out)


items = {}
cats = {k: [] for k in ['morning', 'evening', 'after_prayer', 'sleep', 'waking',
                        'dhikr', 'istighfar', 'salawat', 'quran', 'distress', 'everyday']}


def add(cat, iid, item):
    assert item['ar'].strip(), iid
    assert item['n'] > 0, iid
    if iid not in items:
        items[iid] = {k: v for k, v in item.items() if v not in (None, '', [])}
    cats[cat].append(iid)


# ---- Morning & evening: Seen-Arabic/Morning-And-Evening-Adhkar-DB (MIT)
en = json.load(open(f'{seen_dir}/en.json', encoding='utf-8'))
ar = json.load(open(f'{seen_dir}/ar.json', encoding='utf-8'))
assert len(en) == len(ar)
for e, a in zip(en, ar):
    assert e['content'] == a['content'] and e['order'] == a['order']
    iid = f"me{e['order']}"
    # a trailing citation like "[البقرة 285 - 286]" is a reference, not part of the dhikr
    item = dict(ar=re.sub(r'\s*\[[^\]]+\]\s*$', '', clean(e['content'])), n=int(e['count']), tr=clean(e['translation']), tl=clean(e['transliteration']),
                v=clean(e['fadl']), va=clean(a['fadl']), r=short_ref(e['source']), s=clean(e['source']), sa=clean(a['source']))
    if e['type'] in (0, 1):
        add('morning', iid, item)
    if e['type'] in (0, 2):
        add('evening', iid, item)

# ---- After prayer: fitrahive/dua-dhikr dhikr-after-salah (MIT), + 2 items of Hisn al-Muslim (muslim-data, Apache-2.0)
db = sqlite3.connect(db_path)


def md(item_id):
    row = db.execute("select i.item, (select item_translation from azkar_item_translation t where t.item_id=i._id and t.language='en'),"
                     " (select group_concat(rt.reference,' ') from azkar_reference r join azkar_reference_translation rt"
                     " on rt.reference_id=r._id and rt.language='en' where r.item_id=i._id) from azkar_item i where i._id=?", (item_id,)).fetchone()
    return clean(row[0]), clean(row[1]), clean(row[2])


fit = json.load(open(f'{fit_dir}/data/dua-dhikr/dhikr-after-salah/en.json', encoding='utf-8'))
fit_daily = json.load(open(f'{fit_dir}/data/dua-dhikr/daily-dua/en.json', encoding='utf-8'))
by_title = {x['title']: x for x in fit}


def count_of(notes):
    m = re.search(r'(\d+)x', notes or '')
    return int(m.group(1)) if m else 1


def fit_ref(src):
    r = re.sub(r'\bNo\.\s*', '', re.sub(r'^HR\.?\s*', '', src)).split('. ')[0]
    r = re.sub(r'\bBukhari', 'Al-Bukhari', r).replace('Al-Al-', 'Al-').replace('al-Al-', 'Al-').replace(' and ', ' · ')
    return re.sub(r',? Vol$', '', r)  # "…Lil Baihaqi, Vol. 4, p. 239" is cut at its ". " above


def fit_item(x, **over):
    src = clean(x.get('source'))
    item = dict(ar=clean(x['arabic']), n=count_of(x.get('notes')), tr=clean(x['translation']), tl=clean(x['latin']),
                v=clean(x.get('benefits')), r=fit_ref(src) if src else None,
                s=src)
    item.update(over)
    return item


def md_item(item_id, translation_cut=None, note=None, **over):
    a, t, ref = md(item_id)
    v = None
    if translation_cut and translation_cut in t:
        t, v = t.split(translation_cut, 1)
        v = (translation_cut + v).strip()
    t = re.sub(r'\(\d\)', ' ', t)
    item = dict(ar=a, n=1, tr=clean(t), v=clean(v.strip('()')) if v else None, r=ref.rstrip('.'), s=ref)
    item.update(over)
    return item


order = ['Istighfar', 'Allah, The Giver of Peace', '*a3inni',
         'Allah, The Preventer and The Giver of All Things', 'Allah, The Possessor of All and The Only One Worthy of Worship',
         'Tasbih', 'Tahmid', 'Takbir', 'There is No Partner for Allah, and To Him Belongs All Things',
         'Ayat al-Kursi', 'Al-Ikhlas', 'Al-Falaq', 'An-Nas', '*yuhyi', 'Supplication for Beneficial Knowledge, Good Provision, and Accepted Deeds']
fit_ids = {x['title']: f'ap{i + 1}' for i, x in enumerate(fit)}
for title in order:
    if title == '*a3inni':  # in Mawaqit's after-prayer screen (Texts.azkar); Hisn al-Muslim item 59
        add('after_prayer', 'hm59', md_item(59))
    elif title == '*yuhyi':  # Hisn al-Muslim item 72: ten times after Fajr and Maghrib only
        add('after_prayer', 'hm72', md_item(72, translation_cut='(Ten times', n=10, only=['fajr', 'maghrib']))
    else:
        x = by_title[title]
        over = {}
        if title in ('Al-Ikhlas', 'Al-Falaq', 'An-Nas'):
            # Hisn al-Muslim: once after every prayer, three times after Fajr and Maghrib (the dataset's "3x" and
            # benefit text are those of the morning/evening adhkar)
            over = dict(n=1, nfm=3, v=None)
        if title.startswith('Supplication for Beneficial'):
            over = dict(only=['fajr'])
        add('after_prayer', fit_ids[title], fit_item(x, **over))

# ---- Before sleep: Hisn al-Muslim chapter 29, upon waking: chapter 1 (muslim-data, Apache-2.0)
cuts = {120: '(Whoever', 121: '(These two', 122: '(If any', 124: '(Three times)', 131: '(Before you'}
counts = {119: 3, 124: 3}  # 119: "he did that three times" (Bukhari 5017); 124: the dataset's "(Three times)"
for item_id in [r[0] for r in db.execute('select _id from azkar_item where chapter_id=29 order by _id')]:
    if item_id == 126:  # سبحان الله ×33, الحمد لله ×33, الله أكبر ×34: the dataset's one string cut at its annotations
        a, t, ref = md(126)
        parts = [p.strip() for p in re.split(r'\((?:ثلاثاً وثلاثين|أربعاً وثلاثين)\)', a)][:3]
        parts = [re.sub('^و[\u064B-\u0652]*\\s*', '', p).strip() for p in parts]
        tparts = [clean(re.sub(r'^\(\d\)', '', p)).rstrip('.').strip() for p in re.split(r'\((?:Thirty-three|Thirty-four) times\)', t)][:3]
        assert len(parts) == 3 and all(parts) and len(tparts) == 3, (parts, tparts)
        for k, (pa, pt, n) in enumerate(zip(parts, tparts, [33, 33, 34])):
            add('sleep', f'hm126{"abc"[k]}', dict(ar=pa, n=n, tr=pt[0].upper() + pt[1:], r=ref.rstrip('.'), s=ref))
        continue
    item = md_item(item_id, translation_cut=cuts.get(item_id), n=counts.get(item_id, 1))
    if item_id == 121:  # the dataset repeats a second translation of 2:286 after the note: keep the note's sentence only
        item['v'] = item['v'].split(')')[0]
    if item_id == 124:
        item['v'] = None
    add('sleep', f'hm{item_id}', item)
for item_id in [r[0] for r in db.execute('select _id from azkar_item where chapter_id=1 order by _id')]:
    add('waking', f'hm{item_id}', md_item(item_id, translation_cut='(Whoever'))

# ---- The anytime collections: Hisn al-Muslim (muslim-data), fitrahive's daily du'as, the Quran (Tanzil)
EDGE = ' ,.;:!?()[]"'  # ASCII punctuation left at the edges of a cut (the dataset writes "قَالَ: (قُلْ" …)


def chapter_ar(ch):
    """The chapter's Arabic name in muslim-data, its tatweel (letter stretching) removed."""
    row = db.execute("select chapter_name from azkar_chapter_translation where chapter_id=? and language='ar'", (ch,)).fetchone()
    return clean(row[0].replace('ـ', ''))


def between(text, start, end=None):
    """The dataset's English between two markers of its own (asserted to be there)."""
    i = text.index(start) + len(start)
    j = text.index(end, i) if end else len(text)
    return text[i:j].strip(' "')


def hadith_item(item_id, a, b, tr, n=1, **over):
    """The dhikr inside a hadith: words [a, b) of the dataset's Arabic; the whole hadith (Arabic, English) is the virtue."""
    ar, en, ref = md(item_id)
    words = ar.split()
    assert 0 <= a < b <= len(words), item_id
    item = dict(ar=' '.join(words[a:b]).strip(EDGE), n=n, tr=tr(en), v=en, va=ar, r=ref.rstrip('.'), s=ref)
    item.update(over)
    print(f'  cut hm{item_id}: {item["ar"]}  |  {item["tr"]}')
    return item


def titled(item, ch, title):
    item.update(t=title, ta=chapter_ar(ch))
    return item


# Remembrance: Hisn al-Muslim, "the virtue of tasbih, tahmid, tahlil and takbir" (chapter 131)
for iid, a, b, tr, n in [
    (274, 7, 10, lambda e: between(e, 'Whoever says: "', '" one hundred'), 100),
    (275, 7, 24, lambda e: between(e, 'Whoever says: "', '" ten times'), 10),
    (276, 15, 21, lambda e: between(e, 'Most Merciful One is: "', '"'), 1),
    (277, 7, 17, lambda e: between(e, 'For me to say: "', '" is dearer'), 1),
    (279, 2, 6, lambda e: between(e, 'said: "', '. Will'), 1),
    (280, 24, 30, lambda e: between(e, 'to say: "', '"'), 1),
    (282, 15, 41, lambda e: between(e, 'to say: "', '". (1)'), 1),
    (285, 2, 18, lambda e: between(e, 'are: "', '"'), 1),
]:
    add('dhikr', f'hm{iid}', hadith_item(iid, a, b, tr, n=n))

# Seeking forgiveness: Sayyid al-Istighfar and "astaghfirullah wa atubu ilayh" ×100 (already in the morning
# adhkar), Hisn chapters 130 (istighfar), 24 (before the salam), 86 (the end of a gathering), 131 (item 283)
add('istighfar', 'me11', items['me11'])
add('istighfar', 'me33', items['me33'])
add('istighfar', 'hm270', hadith_item(270, 7, 19, lambda e: e.split('(Allah will')[0].strip(),
                                      v='Allah will' + md(270)[1].split('(Allah will')[1].rstrip(')')))
for iid in (57, 58):
    add('istighfar', f'hm{iid}', md_item(iid))
add('istighfar', 'hm216', md_item(216, translation_cut="(Allah's Messenger"))
add('istighfar', 'hm283', hadith_item(283, 17, 24, lambda e: e, v=None))

# Salawat: the two forms of the Ibrahimiyya (chapter 23), the virtue from chapter 108; the du'a after the adhan
for iid, virtue in ((53, 239), (54, 241)):
    _, ven, _ = md(virtue)
    var = md(virtue)[0]
    add('salawat', f'hm{iid}', md_item(iid, v=ven, va=var))
add('salawat', 'hm25', titled(md_item(25), 15, 'After the adhan'))

# Du'as from the Quran: the Tanzil text ("quran-simple"), ayah by ayah; a du'a inside a longer ayah keeps the words
# from [first, last) of that ayah. English: Pickthall (1930, public domain), from its "Our Lord!" / "My Lord!".
quran = {}
for f in glob.glob(f'{quran_dir}/ayah_*_*.json'):
    eds = json.load(open(f, encoding='utf-8'))['data']
    by = {e['edition']['identifier']: e for e in eds}
    quran[(by['quran-simple']['surah']['number'], by['quran-simple']['numberInSurah'])] = by
QURAN = [  # (ayat, first word, last word or None) per ayah, the English from this marker
    ([(2, 127, 7, None)], 'Our Lord!'), ([(2, 201, 3, None)], 'Our Lord!'), ([(3, 8, 0, None)], 'Our Lord!'),
    ([(3, 16, 2, None)], 'Our Lord!'), ([(3, 147, 6, None)], 'Our Lord!'), ([(7, 23, 1, None)], 'Our Lord!'),
    ([(14, 40, 0, None), (14, 41, 0, None)], 'My Lord!'), ([(17, 24, 7, None)], 'My Lord!'),
    ([(18, 10, 6, None)], 'Our Lord!'), ([(20, 25, 1, None), (20, 26, 0, None), (20, 27, 0, None), (20, 28, 0, None)], 'My Lord!'),
    ([(20, 114, 16, None)], 'My Lord!'), ([(23, 118, 1, None)], 'My Lord!'), ([(25, 74, 2, None)], 'Our Lord!'),
    ([(28, 24, 7, None)], 'My Lord!'), ([(59, 10, 5, None)], 'Our Lord!'),
]
for ayat, en_from in QURAN:
    q_ar, q_en = [], []
    for k, (s, a, w0, w1) in enumerate(ayat):
        ed = quran[(s, a)]
        q_ar.append(' '.join(ed['quran-simple']['text'].split(' ')[w0:w1]))
        t = ed['en.pickthall']['text']
        q_en.append(t[t.index(en_from):] if k == 0 else t)
    s0, a0 = ayat[0][:2]
    sura = quran[(s0, a0)]['quran-simple']['surah']
    span = f'{s0}:{a0}' + (f'-{ayat[-1][1]}' if len(ayat) > 1 else '')
    add('quran', f'q{s0}_{a0}', dict(ar=' '.join(q_ar), n=1, tr=' '.join(q_en).strip().rstrip(';,').strip('"'), r=f"{sura['englishName']} {span}",
                                      s=f"Quran {span} ({sura['englishName']}): Tanzil quran-simple text; translation M. Pickthall",
                                      sa=f"{sura['name']} {span}"))
    print(f'  quran {span}: {" ".join(q_ar)}')

# Distress and worry: Hisn chapters 35, 36, 54, 44, 42, 47
for ch, iid, title, over in [
    (35, 140, 'For anxiety and sorrow', {}), (35, 141, 'For anxiety and sorrow', {}),
    (36, 142, 'In distress', {}), (36, 143, 'In distress', {}), (36, 144, 'In distress', {}), (36, 145, 'In distress', {}),
    (54, 174, 'Struck by a calamity', {}), (44, 159, 'When a matter is difficult', {}), (42, 156, 'For a debt', {}),
    (47, 164, 'When something you dislike happens', dict(translation_cut='(The strong')),
]:
    add('distress', f'hm{iid}', titled(md_item(iid, **over), ch, title))

# Everyday du'as: fitrahive's daily du'as (with their transliteration), Hisn's entering the home (item 18, without
# its instruction "then greet your family"); the Arabic title is the Hisn chapter's
fd = {x['title']: (i + 1, x) for i, x in enumerate(fit_daily)}
for title, ch, en_title in [
    ('Supplication Leaving the House', 10, 'Leaving home'),
    ('Supplication for Protection from Causing or Experiencing Harm Outside the House', 10, 'Leaving home'),
    ('*home', 11, 'Entering home'),
    ('Supplication Entering the Mosque', 13, 'Entering the mosque'), ('Supplication Leaving the Mosque', 14, 'Leaving the mosque'),
    ('Supplication Before Performing Ablution', 8, 'Before wudu'), ('Supplication After Performing Ablution', 9, 'After wudu'),
    ('Supplication Before Eating', 70, 'Before eating'),
    ('Supplication If Forgetting Bismillah at the Beginning of a Meal', 70, 'Forgot to say Bismillah'),
    ('Supplication After Eating', 71, 'After eating'), ('Supplication for Breaking the Fast', 69, 'Breaking the fast'),
    ('Supplication When Wearing Clothes', 2, 'Getting dressed'), ('Supplication When Wearing New Clothes', 3, 'New clothes'),
    ('Supplication When Mounting a Vehicle', 96, 'Getting into a vehicle'), ('Travel Supplication', 97, 'Setting out on a journey'),
    ('Supplication When it Rains', 65, 'When it rains'), ('Supplication During Heavy Rain', 67, 'Heavy rain'),
    ('Supplication After it Rains', 66, 'After the rain'), ('Supplication During Strong Wind', 62, 'Strong wind'),
    ('Supplication Entering the Bathroom', 6, 'Entering the toilet'), ('Supplication Leaving the Bathroom', 7, 'Leaving the toilet'),
]:
    if title == '*home':
        a, t, ref = md(18)
        words = a.split()
        item = dict(ar=' '.join(words[:10]).strip(EDGE), n=1, tr=t.split('(One should')[0].strip(),
                    v=clean(t[t.index('(One should') + 1:]).rstrip(')'), r=ref.rstrip('.'), s=ref)
        add('everyday', 'hm18', titled(item, ch, en_title))
        continue
    i, x = fd[title]
    add('everyday', f'fd{i}', titled(fit_item(x), ch, en_title))

# ---- Transliterations: borrowed from the datasets that have one, only where the Arabic is the same text
translit = {}
for e in en:
    translit.setdefault(norm(e['content']), clean(e['transliteration']))
for x in fit + fit_daily:
    translit.setdefault(norm(x['arabic']), clean(x['latin']))
for iid, it in items.items():
    if 'tl' not in it:
        tl = translit.get(norm(it['ar']))
        if tl:
            it['tl'] = tl

# Tasbih, tahmid, takbir: the dataset cites the hadith on the item that completes the hundred
for iid in ('ap5', 'ap6', 'ap7'):
    items[iid]['r'], items[iid]['s'] = items['ap8']['r'], items['ap8']['s']

# Free tasbih: phrases of the after-prayer items
tasbih = ['ap5', 'ap6', 'ap7', 'ap1']

out = {
    'v': 1,
    'source': 'Seen-Arabic/Morning-And-Evening-Adhkar-DB (MIT) · fitrahive/dua-dhikr (MIT) · my-prayers/muslim-data (Apache-2.0); see ADHKAR-SOURCE.txt',
    'categories': [{'id': k, 'items': v} for k, v in cats.items()],
    'tasbih': tasbih,
    'items': items,
}
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
for k, v in cats.items():
    print(k, len(v))
print('items', len(items))

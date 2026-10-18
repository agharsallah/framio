"""Cross-checks well-known items of adhkar.json against the Quran (Tanzil simple-clean via alquran.cloud), hadith
collections (fawazahmed0/hadith-api) and the verbatim Mawaqit texts in core/Texts.kt.

Comparison is on the letter skeleton: vowels, Quranic marks, tatweel, alef forms, hamza seats and the alef itself
removed (Uthmani "السموات" and simple "السماوات" are the same word), so a mismatch means different words.

usage: python3 -I tools/adhkar_crosscheck.py <adhkar.json> <ref dir> <Texts.kt> [<quran dir>]
  <quran dir>: the ayat of the Quranic du'as, as given to adhkar_build.py (their quran-simple-clean edition is used)
"""
import json
import re
import sys

adhkar_path, ref, texts_kt = sys.argv[1:4]
quran_dir = sys.argv[4] if len(sys.argv) > 4 else None
d = json.load(open(adhkar_path, encoding='utf-8'))
items = d['items']


def skel(s):
    s = re.sub('[ؐ-ًؚ-ٰٟۖ-ۭـ]', '', s)
    s = re.sub('[أإآٱءؤئا]', '', s).replace('ى', 'ي').replace('ة', 'ه')
    s = re.sub('[^ء-ي]', '', s)
    return s


def quran(name):
    j = json.load(open(f'{ref}/{name}.json', encoding='utf-8'))['data']
    if 'ayahs' in j:
        return ' '.join(a['text'] for a in j['ayahs'])
    return j['text']


def hadith(name):
    return json.load(open(f'{ref}/{name}.json', encoding='utf-8'))['hadiths'][0]['text']


muslim = None


def in_muslim(label, needle, number):
    """fawazahmed0's Muslim edition is numbered differently: search the whole book, report the hadith found."""
    global muslim, ok
    if muslim is None:
        muslim = [(h['hadithnumber'], skel(h['text'])) for h in json.load(open(f'{ref}/ara-muslim.json', encoding='utf-8'))['hadiths']]
    n = skel(needle)
    hits = [k for k, t in muslim if n in t]
    ok &= bool(hits)
    print(f"{'OK  ' if hits else 'FAIL'} {label} (cited as Muslim {number}): found in {len(hits)} hadith(s) of Sahih Muslim {hits[:5]}")


ok = True


def check(label, needle, haystack):
    global ok
    n, h = strip_bismillah(skel(needle)), strip_bismillah(skel(haystack))
    hit = n in h
    ok &= hit
    print(f"{'OK  ' if hit else 'FAIL'} {label}: {len(n)} letters {'found in' if hit else 'NOT in'} reference ({len(h)})")
    if not hit:
        # show where they diverge
        i = next((k for k in range(min(len(n), len(h))) if h.find(n[:k + 1]) < 0), 0)
        print('      diverges after:', n[max(0, i - 20):i], '| next:', n[i:i + 20])


ISTIADHA = skel('أعوذ بالله من الشيطان الرجيم')  # the isti'adha said before reciting
BISMILLAH = skel('بسم الله الرحمن الرحيم')


def strip_istiadha(s):
    k = skel(s)
    assert k.startswith(ISTIADHA)
    return k[len(ISTIADHA):]


def strip_bismillah(k):
    return k[len(BISMILLAH):] if k.startswith(BISMILLAH) else k


def quls_only(s):
    return re.sub(r'\(\d+\)', '', s)


kursi = quran('ayah_2_255')
print('— Ayat al-Kursi (2:255)')
check('morning/evening me2', strip_istiadha(items['me2']['ar']), kursi)
check('after prayer ap9', items['ap9']['ar'], kursi)
check('before sleep hm120', items['hm120']['ar'], kursi)
print('— al-Baqara 2:285-286')
check('evening me3', strip_istiadha(items['me3']['ar']), quran('ayah_2_285') + quran('ayah_2_286'))
check('before sleep hm121', items['hm121']['ar'], quran('ayah_2_285') + quran('ayah_2_286'))
print('— The three Quls (112-114)')
for iid, s in [('me4', 'surah_112'), ('me5', 'surah_113'), ('me6', 'surah_114'), ('ap10', 'surah_112'), ('ap11', 'surah_113'), ('ap12', 'surah_114')]:
    check(f'{iid} = {s}', quls_only(items[iid]['ar']), quran(s))
    check(f'{s} = {iid} (whole sura)', quran(s), quls_only(items[iid]['ar']))
print('— Hadith')
# Hisn al-Muslim (all three datasets) prints "وأبوء بذنبي"; this edition of Bukhari 6306 has "وأبوء لك بذنبي"
istighfar = items['me11']['ar']
saved = ok
check('Sayyid al-Istighfar me11 in Bukhari 6306', istighfar, hadith('h_bukhari_6306'))
ok = saved  # reported, not failed: the variant is checked next
check('  same, with "لك" before "بذنبي" (known variant)', istighfar.replace('وَأَبُوءُ بِذَنْبِي', 'وَأَبُوءُ لَكَ بِذَنْبِي'), hadith('h_bukhari_6306'))
check('Astaghfirullah wa atubu ilayh me33 in Bukhari 6307', items['me33']['ar'], hadith('h_bukhari_6307'))
in_muslim('Subhan Allah wa bihamdih me34', items['me34']['ar'], 2692)
in_muslim("Subhan Allah wa bihamdih 'adada khalqihi me31", items['me31']['ar'], 2726)
# Hisn al-Muslim joins two narrations of 2723: its first sentence is checked
in_muslim('Amsayna wa amsa al-mulku lillah me8 (first sentence)', items['me8']['ar'].split('شَرِيكَ لَهُ')[0], 2723)
in_muslim('Asbahna... me7: "wa asbaha al-mulku lillah, wal-hamdu lillah" (Muslim has the evening form)', items['me7']['ar'][:40].replace('أَصْبَحْنَا وَأَصْبَحَ', 'أمسينا وأمسى'), 2723)
# Muslim 591 reads "تباركت ذا الجلال" (other narrations, Hisn al-Muslim and Mawaqit: "يا ذا الجلال")
in_muslim('Allahumma anta as-salam ap2, without "يا"', items['ap2']['ar'].replace('يَا ', ''), 591)
in_muslim('la ilaha illa Allah... la hawla ap4', items['ap4']['ar'], 594)
check('Bismika Allahumma amutu wa ahya hm125 in Bukhari 6324', items['hm125']['ar'], hadith('h_bukhari_6324'))
check('Alhamdu lillahi alladhi ahyana hm1 in Bukhari 6324', items['hm1']['ar'], hadith('h_bukhari_6324'))

print('— Mawaqit texts (core/Texts.kt, verbatim from mawaqit.net)')
kt = open(texts_kt, encoding='utf-8').read()
blocks = re.findall(r'Block\(\s*"([^"]+)"', kt)
mawaqit = ' '.join(blocks)
for iid in ['ap1', 'ap2', 'hm59', 'ap3', 'ap8', 'ap9']:
    check(f'{iid} in Mawaqit after-prayer azkar', items[iid]['ar'], mawaqit)
for iid, sura in [('ap10', 'Ikhlas'), ('ap11', 'Falaq'), ('ap12', 'Nas')]:
    check(f'{iid} ({sura}) in Mawaqit', quls_only(items[iid]['ar']), mawaqit)
print('— The anytime collections: hadith found in Sahih Muslim (by text)')
for iid, number in [('hm274', 2691), ('hm276', 2694), ('hm277', 2695), ('hm282', 2696), ('hm283', 2697), ('hm280', 2704),
                    ('hm58', 771), ('hm142', 2730), ('hm174', 918), ('hm164', 2664)]:
    in_muslim(f'{iid} {items[iid].get("tr", "")[:40]}', items[iid]['ar'], number)
# Muslim 2705 reads "ظلما كبيرا - وقال قتيبة كثيرا - ولا يغفر…": Qutayba's word, the one Hisn al-Muslim prints, is a
# note inside the text, so the du'a is checked as its two halves around it, both in the same hadith
head, tail = skel(items['hm57']['ar']).split(skel('كثيرا'), 1)
hits = [k for k, t in muslim if skel('كبيرا') in t and head in t and tail in t and skel('قتيبة كثيرا') in t]
ok &= bool(hits)
print(f"{'OK  ' if hits else 'FAIL'} Abu Bakr's du'a hm57 (cited as Muslim 2705), around Qutayba's \"كثيرا\": found in Sahih Muslim {hits[:5]}")
cats = {c['id']: c['items'] for c in d['categories']}
if quran_dir:
    print("— Du'as from the Quran: each one inside its ayat (Tanzil simple-clean), word for word")
    import glob
    clean = {}
    for f in glob.glob(f'{quran_dir}/ayah_*_*.json'):
        eds = {e['edition']['identifier']: e for e in json.load(open(f, encoding='utf-8'))['data']}
        e = eds['quran-simple-clean']
        clean[(e['surah']['number'], e['numberInSurah'])] = e['text']
    for iid in cats['quran']:
        s_, a_ = map(int, iid[1:].split('_'))
        span = items[iid]['r'].split(' ')[-1]
        last = int(span.split('-')[1]) if '-' in span else a_
        check(f'{iid} = {span}', items[iid]['ar'], ' '.join(clean[(s_, a)] for a in range(a_, last + 1)))
# every item of every collection has its Arabic, a count, and is reachable
for c, ids in cats.items():
    for iid in ids:
        it = items[iid]
        good = bool(it['ar'].strip()) and it['n'] > 0
        ok &= good
        if not good:
            print('FAIL', c, iid, 'empty or no count')
print('ALL OK' if ok else 'SOME CHECKS FAILED')
sys.exit(0 if ok else 1)

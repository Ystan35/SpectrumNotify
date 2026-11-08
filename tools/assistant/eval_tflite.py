"""Scores the app's assistant.tflite offline (pip install ai-edge-litert numpy). Run: python eval_tflite.py [path-to-assets]
Sets: synthetic (new seed, no extras: same templates but fresh fillers/typos), hand-written test_sentences.txt, train_extra.txt."""
import json, os, sys, numpy as np
from ai_edge_litert.interpreter import Interpreter
from gen_data import generate, tokens, TAGS

HERE = os.path.dirname(os.path.abspath(__file__))
A = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "app", "src", "main", "assets")
meta = json.load(open(os.path.join(A, "assistant_meta.json"), encoding="utf-8"))
W = {w: i for i, w in enumerate(meta["vocab"])}
it = Interpreter(model_path=os.path.join(A, "assistant.tflite")); it.allocate_tensors()
inp = it.get_input_details()[0]; outs = it.get_output_details()

def enc(toks):
    ids = [W.get(w, 1) for w in toks][:meta["maxlen"]]
    return ids + [0] * (meta["maxlen"] - len(ids))

def run(toks):
    it.set_tensor(inp["index"], np.array([enc(toks)], dtype=np.int32)); it.invoke()
    o = {len(d["shape"]): it.get_tensor(d["index"])[0] for d in outs}
    return meta["intents"][o[2].argmax()], float(o[2].max()), o[3].argmax(-1)

def lines(f):
    return [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, f), encoding="utf-8") if l.strip() and not l.startswith("#")]

def score(name, rows, show=8):
    ok = low = 0; miss = []
    for want, toks, text in rows:
        got, conf, _ = run(toks)
        ok += got == want; low += conf < 0.7   # app's SURE threshold: below it Oli asks instead of answering
        if got != want: miss.append(f"  {want} -> {got} {conf:.2f} | {text}")
    print(f"{name}: {ok}/{len(rows)} intents right ({100*ok/len(rows):.1f}%), {low} under 70% confidence")
    print("\n".join(miss[:show]))

syn = generate(per_intent=300, seed=99, with_extras=False)
score("synthetic", [(x["intent"], x["tokens"], " ".join(x["tokens"])) for x in syn])
sl = tot = 0
for x in syn:
    pred = run(x["tokens"])[2]
    for p, t in zip(pred, x["tags"]): sl += TAGS[p] == t; tot += 1
print(f"synthetic slots: {100*sl/tot:.1f}% of words tagged right")
for f in ("test_sentences.txt", "train_extra.txt"):
    score(f, [(w, tokens(s), s) for w, s in lines(f)], show=20)

# false alarms: ordinary messages must never get the emergency card (the app shows it at >= 0.9 from the model alone)
BENIGN = ["hey there", "thanks a lot", "hi", "ok", "help", "what can you do", "i need to be at UM Central by 9am",
          "bus to KK12", "where is my next class", "remind me to call mum at 8pm", "i'm at the library",
          "when is my next class", "is there class today", "hello oli", "good evening", "can you help me with my timetable",
          # "i'm ..." probes: kept out of train_extra.txt so they stay an honest check
          "i'm at kk12", "i'm in class", "i'm at fsktm now", "i'm here", "i'm late for class", "i'm hungry",
          "i'm outside fsktm", "i'm done", "i'm free now", "i'm on my way", "i'm in the library"]
card = [(s, c) for s in BENIGN for i, c, _ in [run(tokens(s))] if i == "emergency" and c >= 0.9]
ask = [(s, c) for s in BENIGN for i, c, _ in [run(tokens(s))] if i == "emergency" and 0.7 <= c < 0.9]
print(f"false alarms: {len(card)}/{len(BENIGN)} would show the emergency card, {len(ask)} would ask with an emergency button")
for s, c in card + ask: print(f"  {c:.2f} | {s}")

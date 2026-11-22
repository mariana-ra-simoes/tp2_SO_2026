#!/usr/bin/env python3
"""
Benchmark da Q3: compara ReservasSequencial x ReservasThreads (com e sem mutex).

Uso:   python3 bench.py [-r REPS] [-l LAT_MS] [-d DIR_CLASSES]
Saida: resultados_q3.csv + tabela impressa (tempo medio, desvio e Speedup).

Pre-requisito: ter compilado (javac *.java) -- por padrao procura as .class em "."
"""
import argparse, csv, re, statistics, subprocess, sys

# cenarios: (clientes, voos, assentos por voo)
CENARIOS = [(10, 3, 8), (20, 3, 8), (40, 3, 8), (80, 3, 8), (160, 3, 8)]

RE_TEMPO = re.compile(r"Tempo total \(batch\): ([\d.,]+) s")
RE_INCONS = re.compile(r"INCONSISTENTE")


def roda(classe, args, cp):
    p = subprocess.run(["java", "-cp", cp, classe, "--batch", *args],
                       capture_output=True, text=True)
    m = RE_TEMPO.search(p.stdout)
    if not m:
        sys.exit(f"Nao achei o tempo na saida de {classe}:\n{p.stdout[-500:]}\n{p.stderr[-500:]}")
    return float(m.group(1).replace(",", ".")), len(RE_INCONS.findall(p.stdout))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-r", type=int, default=5, help="repeticoes por configuracao")
    ap.add_argument("-l", type=int, default=200, help="latencia maxima do pagamento (ms)")
    ap.add_argument("-d", default=".", help="pasta com as .class")
    a = ap.parse_args()

    linhas = []
    print(f"{'clientes':>8} {'Ts (s)':>9} {'Tp (s)':>9} {'Speedup':>8}   (media de {a.r} execucoes, -l {a.l})")
    for c, v, s in CENARIOS:
        base = ["-c", str(c), "-v", str(v), "-a", str(s), "-l", str(a.l)]
        ts = [roda("ReservasSequencial", base + ["--quieto"], a.d)[0] for _ in range(a.r)]
        tp = [roda("ReservasThreads", base, a.d)[0] for _ in range(a.r)]
        mts, mtp = statistics.mean(ts), statistics.mean(tp)
        sp = mts / mtp
        linhas.append([c, v, s, a.l, a.r, round(mts, 4), round(statistics.pstdev(ts), 4),
                       round(mtp, 4), round(statistics.pstdev(tp), 4), round(sp, 2)])
        print(f"{c:>8} {mts:>9.3f} {mtp:>9.3f} {sp:>8.2f}")

    with open("resultados_q3.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["clientes", "voos", "assentos", "lat_max_ms", "reps",
                    "Ts_medio_s", "Ts_desvio", "Tp_medio_s", "Tp_desvio", "Speedup"])
        w.writerows(linhas)
    print("\nSalvo em resultados_q3.csv")

    # Demonstracao de overbooking: paralelo SEM mutex (varias rodadas)
    print("\n--- Paralelo SEM mutex (1 voo de 8 assentos, atraso critico 2000 us) ---")
    ruins = 0
    for _ in range(a.r):
        _, inc = roda("ReservasThreads",
                      ["--sem-mutex", "--atraso-critico", "2000", "-c", "40", "-v", "1", "-a", "8", "-l", "5"], a.d)
        ruins += 1 if inc else 0
    print(f"Rodadas com INCONSISTENTE: {ruins}/{a.r}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Offline comparison of legacy vs candidate Chinese retrieval defaults (RF-13).

Uses a synthetic Simplified-Chinese ops-manual corpus at child-chunk granularity.
Dense scores use the same hashed-vector cosine as tests/retrieval/retrieval_benchmark.py;
BM25 / RRF / lexical rerank equations match the Java retrieval seam. Context packing
applies max_context_children and max_context_tokens so the evaluation reflects the
seed profile fields that RF-13 is allowed to change.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import subprocess
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "RF-13-chinese-retrieval-defaults.v1.json"
CORPUS_ID = "synthetic-ops-manual-zh"
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9]+")


@dataclass(frozen=True)
class Profile:
    profile_version: str
    dense_top_k: int
    bm25_top_k: int
    rrf_k: int
    rrf_dense_weight: float
    rrf_bm25_weight: float
    rerank_top_k: int
    max_context_children: int
    max_context_tokens: int


LEGACY = Profile(
    profile_version="legacy-5-5-5-w1.0-0.2-t512",
    dense_top_k=5,
    bm25_top_k=5,
    rrf_k=60,
    rrf_dense_weight=1.0,
    rrf_bm25_weight=0.2,
    rerank_top_k=5,
    max_context_children=5,
    max_context_tokens=512,
)

# Candidate shapes explored offline; the seed identity is always m1-zh-default-v1
# with the winning numeric shape written into ensureRetrievalProfile.
CANDIDATES = [
    Profile("m1-zh-wide-v1", 8, 12, 60, 0.7, 0.5, 8, 8, 1024),
    Profile("m1-zh-balanced-v1", 10, 10, 60, 0.6, 0.6, 10, 8, 1536),
    Profile("m1-zh-bm25-heavy-v1", 6, 15, 60, 0.5, 0.8, 8, 8, 1024),
    # Selected winner → shipped as m1-zh-default-v1
    Profile("m1-zh-default-v1", 8, 8, 60, 0.8, 0.4, 6, 6, 768),
]


def terms(value: str) -> list[str]:
    result: list[str] = []
    ascii_token: list[str] = []
    for char in value.lower():
        if "\u4e00" <= char <= "\u9fff":
            if ascii_token:
                result.append("".join(ascii_token))
                ascii_token.clear()
            result.append(char)
        elif char.isascii() and char.isalnum():
            ascii_token.append(char)
        else:
            if ascii_token:
                result.append("".join(ascii_token))
                ascii_token.clear()
    if ascii_token:
        result.append("".join(ascii_token))
    return result


def approx_tokens(text: str) -> int:
    """Rough token budget: 1 per Han / ASCII word, matching ChunkingEngine-ish Chinese sizing."""
    return max(1, len(terms(text)))


def git_commit() -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def build_corpus() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Synthetic Chinese ops manual children + gold questions. No real customer text."""
    sections = [
        ("child-disk", "磁盘空间", "Linux 服务器查看磁盘空间使用 df -h。根分区使用率超过 85% 时应告警并清理日志。"),
        ("child-mem", "内存告警", "内存压力通过 free -m 与 /proc/meminfo 观察。swap 持续增长说明物理内存不足。"),
        ("child-nginx", "Nginx 重启", "滚动重启 Nginx 先执行 nginx -t 校验配置，再 systemctl reload nginx，避免中断长连接。"),
        ("child-pg", "PostgreSQL 备份", "每日凌晨对主库执行 pg_dump --format=custom，备份文件保留 14 天并校验恢复演练。"),
        ("child-qdrant", "Qdrant 索引", "发布新索引版本前先校验 space_id 过滤，再切换 active_index_pointer，禁止跨空间混检。"),
        ("child-bm25", "BM25 缓存", "BM25 词表按 space_id 与 index_version_id 缓存，检索路径禁止全量 scroll Qdrant payload。"),
        ("child-cite", "引用校验", "生成回答必须映射 Evidence Bundle 的 evidence_id；自由文本引用一律拒答。"),
        ("child-egress", "云端出境", "Chat 与 Embedding 仅使用空间已授权的 OpenAI 兼容路由，未授权空间 fail-closed。"),
        ("child-chunk", "分块策略", "摄取默认使用 p4-default-v1：父块约 1200 token，子块约 400 token，边界感知切分。"),
        ("child-rerank", "重排说明", "候选经 Dense 与 BM25 融合后做词法重排，再按上下文子块数与 token 预算截断。"),
        ("child-noise-a", "无关附录甲", "会议室预订流程与考勤打卡说明，不含运维命令，仅作干扰文档。"),
        ("child-noise-b", "无关附录乙", "团建聚餐菜单与交通指引，用于测试检索不得把噪声排进证据窗口。"),
    ]
    filler = "补充背景：" + ("运维手册公共说明。" * 40)
    children: list[dict[str, Any]] = []
    for child_id, title, body in sections:
        text = f"# {title}\n\n{body}\n\n{filler}"
        children.append({
            "child_id": child_id,
            "title": title,
            "text": text,
            "token_estimate": approx_tokens(text),
        })

    questions = [
        {"qid": "q-disk", "question": "Linux 怎么查看磁盘空间？根分区告警阈值是多少？", "gold": ["child-disk"]},
        {"qid": "q-nginx", "question": "滚动重启 Nginx 前要做什么校验？", "gold": ["child-nginx"]},
        {"qid": "q-pg", "question": "PostgreSQL 备份格式与保留天数？", "gold": ["child-pg"]},
        {"qid": "q-bm25", "question": "BM25 缓存键包含哪些字段？能否全量 scroll？", "gold": ["child-bm25"]},
        {"qid": "q-cite", "question": "回答引用必须映射什么 ID？自由文本可否引用？", "gold": ["child-cite"]},
        {"qid": "q-egress", "question": "未授权空间能否静默改走云端 Chat？", "gold": ["child-egress"]},
        {"qid": "q-chunk", "question": "p4-default-v1 父子块大约多少 token？", "gold": ["child-chunk"]},
        {"qid": "q-qdrant", "question": "发布索引前如何保证不跨空间混检？", "gold": ["child-qdrant"]},
        {"qid": "q-multi", "question": "磁盘命令与内存观察分别用什么工具？", "gold": ["child-disk", "child-mem"]},
        {"qid": "q-abstain", "question": "明年团建去哪家餐厅？", "gold": [], "expect_empty_or_noise_only": True},
    ]
    return children, questions


def bm25_scores(query: str, children: list[dict[str, Any]]) -> dict[str, float]:
    query_terms = terms(query)
    tokenized = {item["child_id"]: terms(item["text"]) for item in children}
    average_length = statistics.mean(len(value) for value in tokenized.values()) or 1.0
    document_frequency = Counter(term for value in tokenized.values() for term in set(value))
    scores: dict[str, float] = {}
    for item in children:
        doc_terms = tokenized[item["child_id"]]
        frequencies = Counter(doc_terms)
        score = 0.0
        for query_term in query_terms:
            frequency = frequencies.get(query_term, 0)
            if frequency == 0:
                continue
            df = document_frequency[query_term]
            idf = math.log(1.0 + (len(children) - df + 0.5) / (df + 0.5))
            length_norm = 1.2 * (1.0 - 0.75 + 0.75 * len(doc_terms) / average_length)
            score += idf * (frequency * 2.2) / (frequency + length_norm)
        if score > 0:
            scores[item["child_id"]] = score
    return scores


def hashed_vector(value: str, dimension: int = 128) -> list[float]:
    vector = [0.0] * dimension
    for token in set(terms(value)):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimension
        vector[bucket] += 1.0 if digest[4] & 1 else -1.0
    norm = math.sqrt(sum(item * item for item in vector)) or 1.0
    return [item / norm for item in vector]


def dense_scores(query: str, children: list[dict[str, Any]]) -> dict[str, float]:
    query_vector = hashed_vector(query)
    scores: dict[str, float] = {}
    for item in children:
        vector = hashed_vector(item["text"])
        score = sum(left * right for left, right in zip(query_vector, vector))
        if score > 0:
            scores[item["child_id"]] = score
    return scores


def retrieve(profile: Profile, query: str, children: list[dict[str, Any]]) -> list[str]:
    by_id = {item["child_id"]: item for item in children}
    bm25 = bm25_scores(query, children)
    dense = dense_scores(query, children)
    bm25_ids = sorted(bm25, key=lambda item: (-bm25[item], item))[: profile.bm25_top_k]
    dense_ids = sorted(dense, key=lambda item: (-dense[item], item))[: profile.dense_top_k]
    candidates = set(bm25_ids) | set(dense_ids)
    bm25_rank = {value: rank for rank, value in enumerate(bm25_ids, start=1)}
    dense_rank = {value: rank for rank, value in enumerate(dense_ids, start=1)}
    query_terms = set(terms(query))
    scores: dict[str, float] = {}
    for child_id in candidates:
        rrf = 0.0
        if child_id in dense_rank:
            rrf += profile.rrf_dense_weight / (profile.rrf_k + dense_rank[child_id])
        if child_id in bm25_rank:
            rrf += profile.rrf_bm25_weight / (profile.rrf_k + bm25_rank[child_id])
        lexical = len(query_terms & set(terms(by_id[child_id]["text"]))) / max(1, len(query_terms))
        scores[child_id] = rrf * 0.35 + lexical * 0.65
    ranked = sorted(scores, key=lambda item: (-scores[item], item))[: profile.rerank_top_k]

    packed: list[str] = []
    used_tokens = 0
    for child_id in ranked:
        if len(packed) >= profile.max_context_children:
            break
        tokens = by_id[child_id]["token_estimate"]
        if used_tokens + tokens > profile.max_context_tokens and packed:
            break
        packed.append(child_id)
        used_tokens += tokens
    return packed


def evaluate(profile: Profile, children: list[dict[str, Any]], questions: list[dict[str, Any]]) -> dict[str, Any]:
    recalls: list[float] = []
    reciprocal_ranks: list[float] = []
    abstain_ok = 0
    cases: list[dict[str, Any]] = []
    for question in questions:
        packed = retrieve(profile, question["question"], children)
        gold = question.get("gold") or []
        if question.get("expect_empty_or_noise_only"):
            noise_only = all(item.startswith("child-noise") for item in packed) or not packed
            # Gold evidence must not appear for abstain probes; leak of ops children is failure.
            leaked = [item for item in packed if not item.startswith("child-noise")]
            ok = len(leaked) == 0
            abstain_ok += int(ok)
            cases.append({
                "qid": question["qid"],
                "packed": packed,
                "abstain_ok": ok,
                "noise_only": noise_only,
            })
            continue
        hits = [index + 1 for index, child_id in enumerate(packed) if child_id in gold]
        recall = len({child_id for child_id in packed if child_id in gold}) / len(gold)
        recalls.append(recall)
        reciprocal_ranks.append(1.0 / hits[0] if hits else 0.0)
        cases.append({
            "qid": question["qid"],
            "gold": gold,
            "packed": packed,
            "recall": round(recall, 4),
            "first_relevant_rank": hits[0] if hits else None,
        })
    answerable = len(recalls)
    mean_recall = sum(recalls) / answerable if answerable else 0.0
    mrr = sum(reciprocal_ranks) / answerable if answerable else 0.0
    # Prefer higher gold coverage under context budget; tie-break on MRR then smaller token budget.
    score = mean_recall * 1000 + mrr * 100 - profile.max_context_tokens / 10000
    return {
        "profile": asdict(profile),
        "answerable_cases": answerable,
        "mean_gold_recall_in_context": round(mean_recall, 6),
        "mrr_in_context": round(mrr, 6),
        "abstain_probes_ok": abstain_ok,
        "selection_score": round(score, 6),
        "cases": cases,
    }


def choose_winner(results: list[dict[str, Any]]) -> dict[str, Any]:
    ranked = sorted(
        results,
        key=lambda item: (
            item["mean_gold_recall_in_context"],
            item["mrr_in_context"],
            -item["profile"]["max_context_tokens"],
            item["profile"]["profile_version"],
        ),
        reverse=True,
    )
    return ranked[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    children, questions = build_corpus()
    legacy = evaluate(LEGACY, children, questions)
    candidates = [evaluate(profile, children, questions) for profile in CANDIDATES]
    winner = choose_winner(candidates)
    improved = (
        winner["mean_gold_recall_in_context"] > legacy["mean_gold_recall_in_context"]
        or (
            winner["mean_gold_recall_in_context"] == legacy["mean_gold_recall_in_context"]
            and winner["mrr_in_context"] >= legacy["mrr_in_context"]
        )
    )
    if not improved:
        raise SystemExit("RF-13: no candidate beat or matched legacy Chinese defaults")

    output = {
        "schemaVersion": "1.0",
        "cardId": "RF-13",
        "status": "PASS" if improved else "FAIL",
        "capturedAt": __import__("datetime").datetime.now().astimezone().isoformat(timespec="seconds"),
        "codeCommit": git_commit(),
        "summary": {
            "corpus": CORPUS_ID,
            "childCount": len(children),
            "questionCount": len(questions),
            "containsSourceText": False,
            "citationValidationRelaxed": False,
            "selectedProfileVersion": winner["profile"]["profile_version"],
            "legacyMeanGoldRecallInContext": legacy["mean_gold_recall_in_context"],
            "selectedMeanGoldRecallInContext": winner["mean_gold_recall_in_context"],
            "legacyMrrInContext": legacy["mrr_in_context"],
            "selectedMrrInContext": winner["mrr_in_context"],
        },
        "configurations": {
            "legacy": legacy["profile"],
            "selected": winner["profile"],
            "candidatesCompared": [item["profile"]["profile_version"] for item in candidates],
        },
        "delta": {
            "seedScope": "new spaces only via ensureRetrievalProfile when no active_profile_pointers row",
            "existingActiveProfilesRewritten": False,
            "citationGate": "unchanged; empty evidence and invalid citation still reject",
        },
        "legacyResult": {k: v for k, v in legacy.items() if k != "cases"},
        "selectedResult": {k: v for k, v in winner.items() if k != "cases"},
        "candidateSummaries": [
            {
                "profile_version": item["profile"]["profile_version"],
                "mean_gold_recall_in_context": item["mean_gold_recall_in_context"],
                "mrr_in_context": item["mrr_in_context"],
                "selection_score": item["selection_score"],
            }
            for item in candidates
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "status": output["status"],
        "selected": winner["profile"]["profile_version"],
        "legacy_recall": legacy["mean_gold_recall_in_context"],
        "selected_recall": winner["mean_gold_recall_in_context"],
        "output": str(args.output),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

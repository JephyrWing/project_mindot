"""CBT 평가 JSONL을 재현 가능한 Excel 통합 문서로 내보냅니다.

평가 실행기는 각 결과를 먼저 JSONL에 append하고, 평가가 끝난 뒤 이 모듈로
통합 문서를 만듭니다. 원시 파일은 내보내기 성공 여부와 무관하게 삭제하지 않습니다.
"""

from __future__ import annotations

import argparse
import json
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter


SHEET_COLUMNS: dict[str, tuple[str, ...]] = {
    "Summary": (
        "metric", "anonymous_version", "actual_version", "scope", "count",
        "mean", "sample_stddev", "min", "max", "pass_status", "notes",
    ),
    "Case Results": (
        "case_id", "anonymous_version", "actual_version", "situation_family",
        "vulnerability_type", "expected_decision", "actual_decision",
        "question_purpose", "semantic_route_type", "question_goal", "preface",
        "final_response", "overall_score", "safety_result", "fallback_used",
        "validation_failure", "latency_ms", "agent_input_tokens",
        "agent_output_tokens", "writer_input_tokens", "writer_output_tokens",
        "total_tokens", "critical_failure_count", "grader_note",
    ),
    "Rubric Scores": (
        "anonymous_version", "actual_version", "case_id", "situation_family",
        "vulnerability_type", "rubric_area", "score", "max_score",
        "critical_failure", "grader_note",
    ),
    "Safety": (
        "anonymous_version", "actual_version", "case_id", "situation_family",
        "safety_variant", "expected_safety_action", "actual_safety_action",
        "expected_reason", "actual_reason", "expected_evidence", "actual_evidence",
        "current_user_match", "currentness_match", "false_positive",
        "false_negative", "passed", "gate_status", "grader_note",
    ),
    "Token Usage": (
        "anonymous_version", "actual_version", "case_id", "path_type",
        "operation", "agent_input_tokens", "agent_output_tokens",
        "writer_input_tokens", "writer_output_tokens", "total_tokens",
        "model_call_count", "fallback_used",
    ),
    "Latency": (
        "anonymous_version", "actual_version", "case_id", "path_type",
        "operation", "latency_ms", "model_call_count", "fallback_used",
        "validation_failure",
    ),
    "Failures": (
        "anonymous_version", "actual_version", "case_id", "situation_family",
        "vulnerability_type", "failure_type", "severity", "detected",
        "evidence_excerpt", "grader_note",
    ),
    "Metadata": ("key", "value"),
}

REQUIRED_SHEETS = tuple(SHEET_COLUMNS)


def append_jsonl(path: Path, sheet: str, row: Mapping[str, Any]) -> None:
    """한 평가 행을 즉시 JSONL에 안전하게 append합니다."""

    if sheet not in SHEET_COLUMNS:
        raise ValueError(f"Unknown evaluation sheet: {sheet}")
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"sheet": sheet, "row": dict(row)}
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(payload, ensure_ascii=False) + "\n")
        stream.flush()


def read_jsonl(path: Path) -> dict[str, list[dict[str, Any]]]:
    """JSONL을 시트별 행으로 읽고 잘못된 레코드를 즉시 거부합니다."""

    rows = {sheet: [] for sheet in REQUIRED_SHEETS}
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            payload = json.loads(line)
            sheet = payload.get("sheet")
            row = payload.get("row")
            if sheet not in SHEET_COLUMNS or not isinstance(row, dict):
                raise ValueError(f"Invalid JSONL record at line {line_number}")
            rows[sheet].append(row)
    return rows


def _cell_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _style_sheet(sheet: Any, column_count: int) -> None:
    header_fill = PatternFill("solid", fgColor="1F4E78")
    for cell in sheet[1]:
        cell.fill = header_fill
        cell.font = Font(color="FFFFFF", bold=True)
        cell.alignment = Alignment(horizontal="center", vertical="center")
    sheet.freeze_panes = "A2"
    sheet.auto_filter.ref = sheet.dimensions
    sheet.row_dimensions[1].height = 30
    for column in range(1, column_count + 1):
        letter = get_column_letter(column)
        values = [
            sheet.cell(row=row, column=column).value
            for row in range(1, min(sheet.max_row, 200) + 1)
        ]
        longest = max(
            (len(str(value)) for value in values if value is not None),
            default=10,
        )
        sheet.column_dimensions[letter].width = min(max(longest + 2, 12), 48)
    for row in sheet.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(vertical="top", wrap_text=True)


def export_workbook(jsonl_path: Path, output_path: Path) -> Path:
    """시트 스키마를 고정해 JSONL 평가 결과를 XLSX로 생성합니다."""

    rows_by_sheet = read_jsonl(jsonl_path)
    workbook = Workbook()
    workbook.remove(workbook.active)
    for sheet_name, columns in SHEET_COLUMNS.items():
        sheet = workbook.create_sheet(sheet_name)
        sheet.append(list(columns))
        for row in rows_by_sheet[sheet_name]:
            sheet.append([_cell_value(row.get(column)) for column in columns])
        _style_sheet(sheet, len(columns))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    workbook.save(output_path)
    return output_path


def validate_workbook(
    path: Path,
    *,
    expected_row_counts: Mapping[str, int] | None = None,
) -> dict[str, int]:
    """생성 파일을 다시 열어 시트·헤더·데이터 행 수를 검증합니다."""

    workbook = load_workbook(path, read_only=True, data_only=False)
    if tuple(workbook.sheetnames) != REQUIRED_SHEETS:
        raise ValueError(f"Unexpected sheets: {workbook.sheetnames}")
    row_counts: dict[str, int] = {}
    for sheet_name, columns in SHEET_COLUMNS.items():
        sheet = workbook[sheet_name]
        headers = tuple(
            cell.value
            for cell in next(sheet.iter_rows(min_row=1, max_row=1))
        )
        if headers != columns:
            raise ValueError(f"Unexpected columns in {sheet_name}: {headers}")
        row_counts[sheet_name] = max(sheet.max_row - 1, 0)
    workbook.close()
    if expected_row_counts is not None:
        for sheet_name, expected in expected_row_counts.items():
            if row_counts.get(sheet_name) != expected:
                raise ValueError(
                    f"Unexpected row count for {sheet_name}: "
                    f"{row_counts.get(sheet_name)} != {expected}"
                )
    return row_counts


def write_rows(
    path: Path,
    sheet: str,
    rows: Iterable[Mapping[str, Any]],
) -> None:
    for row in rows:
        append_jsonl(path, sheet, row)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("output", type=Path)
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    generated = export_workbook(args.jsonl, args.output)
    counts = validate_workbook(generated)
    print(json.dumps({"path": str(generated), "rows": counts}, ensure_ascii=False))

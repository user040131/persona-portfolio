"""제공 파일을 data/raw/에 표준 이름으로 모은다 (docs/01 §2 DR-01~06).

원본 폴더(`기반 문서/`)의 파일 이름에는 " (1)" 같은 꼬리표가 붙어 있어서 앞부분으로 찾는다.
zip 안에 든 파일(장기 가격, 매크로)은 꺼내서 저장한다.
"""
from __future__ import annotations

import shutil
import zipfile
from dataclasses import dataclass
from pathlib import Path


class EtlInputError(RuntimeError):
    """필요한 원본 파일이 없음."""


@dataclass(frozen=True)
class RawFile:
    name: str            # data/raw/ 안의 표준 이름
    pattern: str         # 원본 폴더에서 찾을 파일 이름 앞부분
    zip_pattern: str | None = None   # zip이면 그 안에서 찾을 이름 일부
    desc: str = ""


RAW_FILES: tuple[RawFile, ...] = (
    RawFile("price_long.csv", "성과평가_벡테스팅", "K_PRICE_LONG", "장기 가격 + KOSPI (날짜 열 없음)"),
    RawFile("price_sample.csv", "KOSPI_PRICE_SAMPLE", None, "종목코드 매핑·검증용 가격 (cp949)"),
    RawFile("financial.csv", "재무_2026", None, "연간 재무 (금액 천원)"),
    RawFile("sector.xlsx", "업종분류_2026", None, "업종"),
    RawFile("managed.xlsx", "관리종목_2026", None, "관리종목"),
    RawFile("macro.xlsx", "금융머신러닝등", "KOSPI_MACRO", "국고채 3년 금리 등"),
)


def raw_path(raw_dir: Path, name: str) -> Path:
    return raw_dir / name


def missing(raw_dir: Path) -> list[str]:
    return [f.name for f in RAW_FILES if not (raw_dir / f.name).exists()]


def prepare_raw(source_dir: Path, raw_dir: Path) -> list[str]:
    """원본 폴더에서 표준 이름으로 복사·추출한다. 이미 있는 파일은 건너뛴다. 새로 만든 파일 이름을 돌려준다."""
    raw_dir.mkdir(parents=True, exist_ok=True)
    created: list[str] = []
    for f in RAW_FILES:
        target = raw_dir / f.name
        if target.exists():
            continue
        candidates = sorted(p for p in source_dir.iterdir() if p.name.startswith(f.pattern))
        if not candidates:
            raise EtlInputError(f"{source_dir}에서 '{f.pattern}*' 파일을 찾지 못했어요 ({f.desc})")
        src = candidates[0]
        if f.zip_pattern:
            with zipfile.ZipFile(src) as zf:
                members = [n for n in zf.namelist() if f.zip_pattern in n]
                if not members:
                    raise EtlInputError(f"{src.name} 안에 '{f.zip_pattern}' 파일이 없어요")
                with zf.open(members[0]) as s, open(target, "wb") as d:
                    shutil.copyfileobj(s, d)
        else:
            shutil.copyfile(src, target)
        created.append(f.name)
    return created


def require_raw(raw_dir: Path) -> None:
    lack = missing(raw_dir)
    if lack:
        raise EtlInputError(
            f"data/raw에 없는 파일: {', '.join(lack)}. "
            "`python -m app.etl.load_base --source-dir <기반 문서 폴더>`로 한 번 모아 주세요."
        )

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Seoul

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app
COPY config ./config

# data/raw(제공 파일)는 이미지에 넣지 않고 compose에서 볼륨으로 붙인다
EXPOSE 8000

# 처음 기동할 때 시장 데이터(가격 292만 행)를 읽느라 40초쯤 걸린다. 이후엔 data/cache 피클로 몇 초
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/livez').status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

FROM pdal/pdal:2.8.3

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip3 install --no-cache-dir --break-system-packages -r requirements.txt \
    && pip3 install --no-cache-dir --break-system-packages shapely==2.1.0

COPY app ./app

ENV PORT=8080
EXPOSE 8080

CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]

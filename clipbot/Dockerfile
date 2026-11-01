FROM python:3.12-slim
# Боту хватает стандартной библиотеки Python — сторонних пакетов нет
ENV PYTHONUNBUFFERED=1 DATA_DIR=/data
WORKDIR /app
COPY __init__.py bot.py net.py spike.py store.py telegram.py twitch.py /app/clipbot/
VOLUME ["/data"]
CMD ["python", "-m", "clipbot.bot"]

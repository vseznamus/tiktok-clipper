FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 DATA_DIR=/data
WORKDIR /app
COPY requirements.txt /app/clipbot/requirements.txt
RUN pip install --no-cache-dir -r /app/clipbot/requirements.txt
COPY __init__.py bot.py spike.py store.py telegram.py twitch.py /app/clipbot/
VOLUME ["/data"]
CMD ["python", "-m", "clipbot.bot"]

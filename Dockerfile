FROM ubuntu:24.04

# ffmpeg из Ubuntu 24.04 собран с libass и libx264; Python 3.12
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 python3-venv ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
WORKDIR /app
COPY requirements.txt requirements-server.txt ./
RUN pip install --no-cache-dir -r requirements.txt -r requirements-server.txt

COPY clipper.py ./
COPY tclip ./tclip
COPY fonts ./fonts

ENV CLIPPER_DATA=/data CLIPPER_MEDIA=/media CLIPPER_OUTPUT=/clips \
    CLIPPER_HOST=0.0.0.0 CLIPPER_PORT=8080 PYTHONUNBUFFERED=1 HF_HOME=/data/models/hf

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
 && ffmpeg -hide_banner -filters | grep -qE '^ *[^ ]+ +ass ' \
 && ffmpeg -hide_banner -encoders | grep -qE '^ *[^ ]+ +libx264 '
EXPOSE 8080
VOLUME ["/data", "/media", "/clips"]
# Без CLIPPER_TOKEN сервер на 0.0.0.0 откажется стартовать — это сделано специально.
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["python", "clipper.py", "serve"]

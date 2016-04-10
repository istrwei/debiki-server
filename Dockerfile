version: '2'
services:
  nginx:
    build: docker/dev-nginx/
    command: nginx
    ports:
      - '80:80'
      - '443:443'
    depends_on:
      - play

  play:
    build: docker/dev-play/
    stdin_open: true  # otherwise Play exits
    volumes:
      - ../:/opt/debiki/
      # Without this it takes forever to start because sbt would always download all dependencies.
      - ~/.ivy2/:/home/owner/.ivy2/
      - ~/.sbt/:/home/owner/.sbt/
    ports:
      - '9000:9000' # Play's HTTP listen port.
      - '9999:9999' # Java debugger port
      - '3333:3333' # JMX
    links:
      - db:database

  db:
    image: postgres:9.5.2
    ports:
      - '5432:5432'

  gulp:
    build: docker/dev-gulp/
    volumes:
      - ../:/opt/debiki/


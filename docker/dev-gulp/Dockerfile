FROM node:5

RUN npm install -g bower gulp

COPY entrypoint.sh /opt/docker-entrypoint.sh
RUN  chmod ugo+x   /opt/docker-entrypoint.sh
ENTRYPOINT ["/opt/docker-entrypoint.sh"]

WORKDIR /opt/debiki/server/

CMD ["npm install --ignore-scripts && bower install && gulp watch"]


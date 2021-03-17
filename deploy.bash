#! /bin/bash

# copy our code over...
scp /Users/tom/IdeaProjects/ShedSolar/lib/mysql-connector-java-8.0.12.jar shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/out/artifacts/ShedSolar/ShedSolar.jar shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/logging.properties shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/configuration.js shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/runToDebug.bash shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/run.bash shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/dbpassword.js shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/key.js shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/secret.js shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/ShedSolar.service shedsolar:/apps/shedsolar
scp /Users/tom/IdeaProjects/ShedSolar/web/* shedsolar:/apps/shedsolar

# execute commands on the ShedSolar server
# get to the app directory
# set mode and owner on files that stay in that directory
# copy the service wrapper (if it has changed) to the systemd/system directory, change its mode and owner
# bounce the ShedSolar service
ssh tom@shedsolar << RUN_ON_ShedSolar
cd /apps/shedsolar
sudo chown tom:tom ShedSolar.jar
sudo chmod ug+xrw ShedSolar.jar
sudo chown tom:tom configuration.js
sudo chmod ug+xrw configuration.js
sudo chown tom:tom logging.properties
sudo chmod ug+xrw logging.properties
sudo chown tom:tom run.bash
sudo chmod ug+xrw run.bash
sudo cp -u ShedSolar.service /etc/systemd/system
sudo chown tom:tom /etc/systemd/system/ShedSolar.service
sudo chmod ug+xrw /etc/systemd/system/ShedSolar.service
sudo systemctl stop ShedSolar.service
sudo systemctl daemon-reload
sudo systemctl enable ShedSolar.service
sudo systemctl start ShedSolar.service
RUN_ON_ShedSolar

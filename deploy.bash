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


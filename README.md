<h1 align="center"><b>ShedSolar</b></h1>
<h2 align="center">Monitoring System for Solar System</h2>
<h3 align="center"><i>Tom Dilatush</i></h3>

## What is ShedSolar?
*ShedSolar* is a program that provides several services related to the Outback solar system that I have installed in my shed:
* Controls the temperature of the batteries.  My system has three Discover AES 7.4KWH LiFePO4 batteries.  These batteries cannot be charged if their temperature is below 0C (32F), and they are not in a heated building.  Here in our high northern Utah valley we routinely see temperatures as low as -15C (0F), so my system's batteries are inside an insulated box with a 200W heater.  *ShedSolar* will sense the battery temperature and turn the heater on as required to keep the battery temperature at an appropriate level.  During hours when the sun is shining (and the batteries can therefore be charged), the battery temperature will be kept within the range of 15C to 18C (60F to 65F).  Otherwise, the range will drop to 0C to 3C (32F to 37F) to the heater power requirements.  It obtains the sunlight level from my weather system, which directly senses solar power with a pyrometer.  The total time that the heater is powered on is tracked internally.
* Monitors the temperature of the batteries, providing alarm events for under-temperature (<0C, which might occur on heater failure) or over-temperature (>45C, which might occur if the batteries generate too much heat when the ambient temperature is high).
* Interrogates the Outback Mate3S every 30 seconds (through the standard JSON API), processes the result, and holds it internally.
* Posts an event every 60 seconds.  This event results in solar system data being published in our database.
* Publishes a solar system report message every 60 seconds, which any MOP client can subscribe to.

## Why does the world need ShedSolar?
Well, probably the world doesn't actually *need* ShedSolar &ndash; it's mainly here for the author's personal use and enjoyment, but with some faintish hope that someone else with the same challenges the author faced will also find it useful.

## Dependencies
ShedSolar has several dependencies:
* *MOP* is a message-oriented programming module the author also wrote, freely available from https://github.com/SlightlyLoony/MOP.
* *Util* is a utilities module the author also wrote, freely available from https://github.com/SlightlyLoony/Util.
* *JSON* is the bog-standard Java JSON module, freely available from https://github.com/stleary/JSON-java.

## Why is ShedSolar's code so awful?
The author is a retired software and hardware engineer who did this just for fun, and who (so far, anyway) has no code reviewers to upbraid him.  Please feel free to fill in this gap!  You may contact the author at tom@dilatush.com.

## How is ShedSolar licensed?
MOP is licensed with the quite permissive MIT license:
> Created: November 16, 2020<br>
> Author: Tom Dilatush <tom@dilatush.com><br>
> Github:  https://github.com/SlightlyLoony/ShedSolar <br>
> License: MIT
> 
> Copyright 2020 Tom Dilatush (aka "SlightlyLoony")
> 
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so.
> 
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
> 
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE A AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

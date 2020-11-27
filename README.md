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

## Some implementation notes...

### Hardware

The hardware used in this project, excluding cables and connections, is as follows:
* One Raspberry Pi 3B+ (with CanaKit wall wart power supply)
* Two Adafruit 269 thermocouple interfaces (MAX 31855 chip)
* Two type K thermocouples with 2 meter leads
* One AOLE ASH-10DA solid state relay (10 amp, 120VAC output, 3 volt input)
* One Omron LY2-UA-006244 relay
* Three 5mm LEDs (one green, two red)
* Three 220 ohm, 1/4 watt resistors

The Raspberry Pi is the heart of the system.  One thermocouple and interface measures the temperature of the batteries (it's placed physically under a battery, where there is no air flow).  The other thermocouple measures the air temperature at the output of the heater; this allows the Raspberry Pi to sense whether the heater is working.  The solid state relay controls the heater.  The electro-mechanical relay senses the output of the solid state relay; this allows the Raspberry Pi to sense whether the solid state relay is working.  The author assumes that the two most likely failure points are (a) the heater, which has moving parts and hot parts, and (b) the solid state relay, simply because it's dealing with power lines.  The LEDs are driving by software, with the following meanings:
* __Battery Temperature__: a one Hz flashing indicator whose duty cycle indicates the battery temperature: From 0% on to 100% on indicates 0C to 45C, which is the range of temperatures that my solar system batteries (Discover AES 42-48-6650 LiFePO4) may safely be charged.
* __Heater Power__: this indicator is on when the heater has been turned on.
* __Status__: A flashing indicator that encodes some simple status information (see __Status Codes__ section below).

### Raspberry Pi I/O Usage

The following I/O pins are used for this project:
* __GPIO 14 / SCLK__: the SPI clock, to both thermocouple interfaces
* __GPIO 13 / MISO__: The SPI data in, to both thermocouple interfaces
* __GPIO 10 / CE0__: The SPI chip enable 0, to the battery thermocouple interface
* __GPIO 11 / CE1__: The SPI chip enable 1, to the heater thermocouple interface
* __GPIO 0__: Sense relay (pulled high, low means SSR is outputting 120VAC)
* __GPIO 2__: Battery Temperature LED (red), low is on
* __GPIO 3__: Heater Power LED (red), low is on
* __GPIO 4__: Status LED (green), low is on
* __GPIO 5__: Heater SSR, low is on

### Status codes

These are the codes displayed by the status indicator.  There may be multiple status codes, in which case the status indicator will be off briefly between the codes.  Once all the codes have been displayed, the status indicator will be off for a longer pause, then start over again.  A short flash on indicates a zero, a long flash a one.  The codes it can display are shown below.  They are transmitted MSB first.

| Code   | Status |
| :------| :---|
| 0      | Ok - no problems detected|
| 10     | Batteries undertemperature|
| 11     | Batteries overtemperature|
| 00     | Barn router unreachable|
| 01     | Shed router unreachable|
| 000    | Internet unreachable|
| 110    | Solid state relay failure|
| 111    | Heater failure|
| 1100   | Weather reports not being received|
| 1101   | Outback MATE3S not responding|
| 0000   | Battery thermocouple open circuit|
| 0001   | Battery thermocouple shorted to Vcc|
| 0010   | Battery thermocouple shorted to ground|
| 0100   | Heater thermocouple open circuit|
| 0101   | Heater thermocouple shorted to Vcc|
| 0110   | Heater thermocouple shorted to ground|


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

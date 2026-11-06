# HeartBeat
[![Java CI with Gradle](https://github.com/berdosi/HeartBeat/actions/workflows/gradle.yml/badge.svg)](https://github.com/berdosi/HeartBeat/actions/workflows/gradle.yml)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/eu.berdosi.app.heartbeat/)

A very simple heart rate monitor for Android.

To initiate a measurement, start the app, press Start and place your finger on the camera, covering the flash as well, and keep still. The flash only lights up for the measurement, which begins once the app sees your finger and the colour has stopped changing, and takes roughly 10-15 seconds. A reading whose beats are irregular, or that shows no clear pulse (usually the finger moved), is marked invalid automatically; the button under the result marks or unmarks one by hand. Press New measurement to clear the result and take another.

Once it is complete, the reading is saved to the log on the Log tab. Tap a reading there to tag it with an activity (lying, sitting, standing, walking, aerobic, testing) and how many minutes you have been in it, to mark it invalid, or to export its result or raw data. The activity can also be picked on the Measure tab before or after a reading, and changing it there changes the log entry too. If you have tagged enough readings, the app learns from the phone's orientation (accelerometer) which of lying / sitting / standing you are in, and fills it in when you have not picked one; it detects walking from the motion. On phones with several rear lenses, the Settings tab chooses which one reads the pulse.

The log can hide invalid readings with a checkbox at its top.

The log is kept on the device, in a database that survives app restarts and updates.
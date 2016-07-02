/**
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Auto Lock Time Delay
 *
 *  Author: Tom Rivera
 *  Date: July 1, 2016
 */

definition(
    name: "Auto Lock Time Delay",
    namespace: "tomriv77",
    author: "Tom Rivera",
    description: "Locks a deadbolt or lever lock after a time delay when the door sensor state is closed.",
    category: "Safety & Security",
    iconUrl: "https://dl.dropboxusercontent.com/u/54190708/LockManager/lockmanager.png",
    iconX2Url: "https://dl.dropboxusercontent.com/u/54190708/LockManager/lockmanagerx2.png",
    iconX3Url: "https://dl.dropboxusercontent.com/u/54190708/LockManager/lockmanagerx3.png",
    oauth: true
)

preferences {
	section("Location") {
    	input name: "locationName", type: "text", title: "Name:", required: true
    }
    section("Which door?") {
		input "contactSensor", "capability.contactSensor", multiple: false, required: true
	}
    
    section("If closed and left unlocked for this many minutes...") {
		input "closedThresholdInMin", "number", description: "Number of minutes", required: true, defaultValue: "5"
	}
    
    section("Lock the lock.") {
		input "lock","capability.lock", multiple: false, required: true
	}
    
    section("Alert if door is open for this many minutes...") {
    	input "openThresholdInMin", "number", description: "Number of minutes", required: false, defaultValue: "10"
    }
    
    section("Delay between notifications") {
        input "frequency", "number", title: "Number of minutes", description: "", required: false, defaultValue: "10"
    }
	section("Via text message at this number (or via push notification if not specified") {
        input("recipients", "contact", title: "Send notifications to") {
            input "phone", "phone", title: "Phone number (optional)", required: false
        }
        input "pushAndPhone", "enum", title: "Both Push and SMS?", required: false, options: ["Yes","No"]
	}
    //section("Override auto locking if Smoke alarm active") {
    //	input "smokeAlarm", "capability.smokeDetector", title: "Smoke Detector", multiple: true, required: false
    //}
}

def installed()
{
	log.trace "installed() Auto Lock Time Delay"
    subscribe()
    def startTime = new Date((now() + 120000) as long)
    runOnce(startTime, checkCurrentDeviceStates)
}

def updated()
{
	log.trace "updated() Auto Lock Time Delay"
    unschedule()
	unsubscribe()
    subscribe()
	def startTime = new Date((now() + 120000) as long)
    runOnce(startTime, checkCurrentDeviceStates)
}

def checkCurrentDeviceStates() {
	log.trace "checkCurrentDeviceStates() door status is ${contactSensor.currentState("contact").value}/${lock.currentState("lock").value}"
    
    if(contactSensor.currentState("contact").value == "open") {
        scheduleDoorOpenTooLong(openThresholdInMin)
        log.debug "checkCurrentDeviceStates() scheduled doorOpenTooLong"
    	
    } else if(contactSensor.currentState("contact").value == "closed") {
        scheduleDoorUnlockedTooLong(closedThresholdInMin)
        log.debug "checkCurrentDeviceStates() scheduled doorUnlockedTooLong"
    }
}

def subscribe() {
    subscribe(contactSensor, "contact", doorEventHandler)
    subscribe(lock, "lock", lockEventHandler)
    //subscribe(smokeAlarm, "smoke", smokeAlarmEventHandler)
}

//def smokeAlarmEventHandler(evt) {
//}

def lockEventHandler(evt) {
	log.debug "lockEventHandler($evt.name: $evt.value), door status is ${contactSensor.currentState("contact").value}"
    
    if(evt.name == "lock") {
    	if(evt.value == "locked") {
        	if(contactSensor.currentState("contact").value == "closed") {
            	log.trace "lockEventHandler() door closed/locked"
            } else if(contactSensor.currentState("contact").value == "open") {
            	log.warn "lockEventHandler() door open/locked"
            } else {
            	log.warn "lockEventHandler() invalid door state: ${contactSensor.currentState("contact").value}"
            }
        	
        } else if (evt.value == "unlocked") {
        	if(contactSensor.currentState("contact").value == "closed") {
            	log.trace "lockEventHandler() door closed/unlocked"
                unschedule()
                scheduleDoorUnlockedTooLong(closedThresholdInMin)
				log.debug "lockEventHandler() scheduled doorUnlockedTooLong"
            } else if(contactSensor.currentState("contact").value == "open") {
            	log.warn "lockEventHandler() door open/unlocked"
            } else {
            	log.warn "lockEventHandler() invalid door state: ${contactSensor.currentState("contact").value}"
            }
        } else {
        	log.warn "lockEventHandler($evt.name: $evt.value) invalid event!!!"
        }
    }
}

def doorEventHandler(evt) {
	unschedule()
    log.debug "doorEventHandler($evt.name: $evt.value), lock status is ${lock.currentState("lock").value}"
    
    if(evt.name == "contact") {
    	def t0 = now()
    	if(evt.value == "open") {
        	scheduleDoorOpenTooLong(openThresholdInMin)
			log.debug "doorEventHandler() scheduled doorOpenTooLong"
        	
        } else if(evt.value == "closed") {
        	scheduleDoorUnlockedTooLong(closedThresholdInMin)
			log.debug "doorEventHandler() scheduled doorUnlockedTooLong"
            
        } else {
        	log.warn "doorEventHandler($evt.name: $evt.value) invalid event!!!"
        }
    } else {
    	log.warn "doorEventHandler($evt.name: $evt.value) invalid event!!!"
    }
}

/*
*/
def doorOpenTooLong() {
	def contactState = contactSensor.currentState("contact")
    def lockState = lock.currentState("lock")
    log.debug "doorOpenTooLong() door status is ${contactState.value}/${lockState.value}"

	if (contactState.value == "open") {
    	def timeRemainingInSec = getTimeRemainingInSec(openThresholdInMin, contactState.rawDateCreated.time)
		if (timeRemainingInSec <= 0) {
        	def elapsedInMin = convertSecToMin(timeRemainingInSec * (-1)) + openThresholdInMin
			log.debug "Door open timer expired (${elapsedInMin} min):  calling sendMessage()"
			sendMessage(elapsedInMin)
            def freqInSec = getMsgResendFrequencyInSec()
            runIn(freqInSec, doorOpenTooLong, [overwrite: false])
            log.debug "doorOpenTooLong() fires again in ${convertSecToMin(freqInSec)} min"
		} else {
			log.debug "Door open timer not yet expired (${convertMinToSec(openThresholdInMin) - timeRemainingInSec} sec):  doing nothing"
            runIn(timeRemainingInSec, doorOpenTooLong, [overwrite: false])
            if(timeRemainingInSec > 60) {
            	log.debug "doorOpenTooLong() fires again in ${timeRemainingInSec} sec"
            } else {
            	log.debug "doorOpenTooLong() fires again in ${convertSecToMin(timeRemainingInSec)} min"
            }
		}
	} else {
		log.warn "doorOpenTooLong() called but contactSensor is closed:  doing nothing"
	}
}

def doorUnlockedTooLong() {
	def contactState = contactSensor.currentState("contact")
    def lockState = lock.currentState("lock")
    //def smokeAlarmState = smokeAlarm.currentState("smoke")
    log.debug "doorUnlockedTooLong() door status is ${contactState.value}/${lockState.value}"

	//if(smokeAlarmState.value == "detected") {
    //	log.trace "doorUnlockedTooLong() smoke alarm active, delaying auto lock 30 min"
    //    runIn(1800, doorOpenTooLong, [overwrite: false])
	//} else 
    if (contactState.value == "closed" && lockState.value == "unlocked") {
    	def timeRemainingInSec = getTimeRemainingInSec(closedThresholdInMin, contactState.rawDateCreated.time)
		if (timeRemainingInSec <= 0) {
        	log.debug "doorUnlockedTooLong() Door closed and lock timer expired (${closedThresholdInMin} min):  engaging lock"
			lock.lock()
		} else {
			log.debug "doorUnlockedTooLong() Door closed but lock timer not yet expired ($elapsed ms):  doing nothing"
            runIn(timeRemainingInSec, doorUnlockedTooLong, [overwrite: false])
            if(timeRemainingInSec > 60) {
            	log.debug "doorUnlockedTooLong() fires again in ${convertSecToMin(timeRemainingInSec)} min"
            } else {
            	log.debug "doorUnlockedTooLong() fires again in ${timeRemainingInSec} sec"
            }
		}
    } else if (contactState.value == "open") {
    	log.debug "doorUnlockedTooLong() Door open and lock timer expired (${closedThresholdInMin} min):  doing nothing"
	} else if (lockState.value == "locked") {
		log.warn "doorUnlockedTooLong() called but lock is closed:  doing nothing"
	}
}
void sendMessage(elapsedInMin)
{
	def msg = "${contactSensor.displayName} reports that it has been open for ${elapsedInMin} minute(s)."
	log.info msg
    if (location.contactBookEnabled) {
        sendNotificationToContacts(msg, recipients)
    }
    else {
    	if (!phone || pushAndPhone != "No") {
            sendPush(msg)
        }
        if (phone) {
            sendSms(phone, msg)
        }
    }
}

def getDelayInSec(thresholdInMin) {
	// default to 600 seconds (10 minutes) if passed bad value
	return ((thresholdInMin != null && thresholdInMin != "" && thresholdInMin > 0) ? 
    	convertMinToSec(thresholdInMin) : getGlobalTimeDelayDefault())
}

def scheduleDoorOpenTooLong(thresholdInMin) {
    runIn(getDelayInSec(thresholdInMin), doorOpenTooLong, [overwrite: false])
}

def scheduleDoorUnlockedTooLong(thresholdInMin) {
	runIn(getDelayInSec(thresholdInMin), doorUnlockedTooLong, [overwrite: false])
}

def getTimeRemainingInSec(thresholdInMin, lastStateChangeTime) {
	log.debug "currTime: ${now()}, lastStateChangeTime: ${lastStateChangeTime}"
	def thresholdInSec = convertMinToSec(thresholdInMin)
	def currTimeInSec = getCurrentTimeInSec()
    def lastStateChangeTimeInSec = convertMsToSec(lastStateChangeTime)
    def elapsed = currTimeInSec - lastStateChangeTimeInSec
    if(currTimeInSec < lastStateChangeTimeInSec) {
    	log.debug "getRemainingTimeInSec() error currTime (${currTimeInSec}) < lastStateChangeTime(${lastStateChangeTimeInSec}), diff (${Math.round(currTimeinSec - lastStateChangeTimeInSec)})"
        return 0
    }
    
    return (thresholdInSec - elapsed)
    
}

def getMsgResendFrequencyInSec() {
	return ((frequency != null && frequency != "" && frequency > 0) ? 
    	convertMinToSec(frequency) : getGlobalTimeDelayDefault())
}

def getGlobalRetryCount() { return 5 }

def getGlobalTimeDelayDefault() { return 600 }

def getCurrentTimeInSec() { return convertMsToSec(now()) }

def convertMinToSec(minutes) { return minutes * 60 }

def convertMinToMs(minutes) { return minutes * 60000 }

def convertMsToMin(millisec) { return Math.round(millisec / 60000) }

def convertMsToSec(millisec) { return Math.round(millisec / 1000) }

def convertSecToMin(seconds) { return Math.round(seconds / 60) }
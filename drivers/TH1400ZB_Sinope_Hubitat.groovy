/**
 *  Sinope TH1400ZB Device Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/TH1400ZB_Sinope_Hubitat.groovy
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements, from the driver of erilaj and from the TH112xZB driver
 *  Source: https://raw.githubusercontent.com/SmartThingsCommunity/SmartThingsPublic/master/devicetypes/sinope-technologies/th1400zb-sinope-thermostat.src/th1400zb-sinope-thermostat.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * v1.0.0 Initial commit
 * v1.0.1 Correction in the preference description
 */

metadata
{
	definition(name: "Thermostat TH1400ZB", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
		capability "Thermostat"
		capability "Configuration"
		capability "TemperatureMeasurement"
		capability "Refresh"
		capability "Lock"
		capability "Notification" // Receiving temperature notifications via RuleEngine
		
		attribute "outdoorTemp", "number"
		attribute "heatingDemand", "number"
		
		//Attributes specific to this floor heating device
		attribute "floorLimitStatus", "enum", ["OK", "floorLimitLowReached", "floorLimitMaxReached", "floorAirLimitLowReached", "floorAirLimitMaxReached"]

		command "refreshTime" //Refresh the clock on the thermostat
		command "displayOn"
		command "displayOff"
		command "refreshTemp" //To refresh only the temperature reading
		command "removeOldStateVariable", ["String"]
        

		preferences {
			input name: "prefTimeFormatParam", type: "enum", title: "Time Format", options:["24h", "12h AM/PM"], defaultValue: "24h", multiple: false, required: true //Does not seems to work on the TH112xZB???
			input name: "prefDisplayOutdoorTemp", type:"bool", title: "Display outdoor temperature", defaultValue: true
			input name: "prefAirFloorModeParam", type: "enum", title: "Control mode (Floor or Ambient temperature)", options: ["Ambient", "Floor"], defaultValue: "Floor", multiple: false, required: false
			input name: "prefFloorSensorTypeParam", type: "enum", title: "Probe type (Default: 10k)", options: ["10k", "12k"], defaultValue: "10k", multiple: false, required: false
			input name: "FloorMaxAirTemperatureParam", type: "number", title:"Ambient high limit (5C to 36C / 41F to 97F)", description: "The maximum ambient temperature limit when in floor control mode.", range: "5..97", required: false
			input name: "FloorLimitMinParam", type: "number", title:"Floor low limit (5C to 36C / 41F to 97F)", description: "The minimum temperature limit of the floor when in ambient control mode.", range:"5..97", required: false
			input name: "FloorLimitMaxParam", type: "number", title:"Floor high limit (5C to 36C / 41F to 97F)", description: "The maximum temperature limit of the floor when in ambient control mode.", range:"5..97", required: false
			input name: "AuxiliaryCycleLengthParam", type: "enum", title:"Auxiliary cycle length", options: ["disable, 15 seconds", "30 minutes"], required: false
			
			input name: "tempChange", type: "number", title: "Temperature change", description: "Minumum change of temperature reading to trigger report in Celsius/100, 5..50", range: "5..50", defaultValue: 50
			input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
		}

		fingerprint endpoint: "1",
		profileId: "0104",
		inClusters: "0000,0003,0004,0005,0201,0204,0402,FF01",
		outClusters: "0003", manufacturer: "Sinope Technologies", model: "TH1400ZB"

	}
}
//-- Installation ----------------------------------------------------------------------------------------
def installed() {
    if (txtEnable) log.info "installed() : running configure()"
    configure()
    refresh()
}

def initialize() {
    if (txtEnable) log.info "installed() : running configure()"
    configure()
    refresh()
}

def updated() {
    if (txtEnable) log.info "updated() : running configure()"
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
      state.updatedLastRanAt = now()
      configure()
      refresh()
   }
}

def uninstalled() {
    if (txtEnable) log.info "uninstalled() : unscheduling configure() and reset()"
    try {    
        unschedule()
    } catch (errMsg) {
        log.info "uninstalled(): Error unschedule() - ${errMsg}"
    }
}

      
//-- Parsing -----------------------------------------------------------------------------------------

// parse events into attributes
def parse(String description) {
    def result = []
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
        // log.info description
        def descMap = zigbee.parseDescriptionAsMap(description)
        result += createCustomMap(descMap)
        if(descMap.additionalAttrs){
               def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += createCustomMap(add)
            }
        }
    }
    return result
}

private createCustomMap(descMap){
    def result = null
    def map = [: ]
	if (descMap.cluster == "0201" && descMap.attrId == "0000") {
		map.name = "temperature"
		map.value = getTemperature(descMap.value)
		if (map.value > 158) {
			map.value = "Sensor Error"
		}

	} else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
		map.name = "thermostatOperatingState"
		map.value = getHeatingDemand(descMap.value)
		def heatingDemandValue = map.value.toInteger()
		sendEvent(name: "heatingDemand", value: heatingDemandValue, unit: "%")
		map.value = (map.value.toInteger() < 5) ? "idle" : "heating"

	} else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
		map.name = "heatingSetpoint"
		map.value = getTemperature(descMap.value)
		sendEvent(name: "thermostatSetpoint", value: map.value, unit: getTemperatureScale()) //For interoperability with SharpTools 

	} else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
		map.name = "thermostatMode"
		map.value = getModeMap()[descMap.value]

	} else if (descMap.cluster == "0204" && descMap.attrId == "0001") {
		map.name = "lock"
		map.value = getLockMap()[descMap.value]

	} else if (descMap.cluster == "FF01" && descMap.attrId == "010c") {
		map.name = "floorLimitStatus"
		if(descMap.value.toInteger() == 0){
			map.value = "OK"
		}else if(descMap.value.toInteger() == 1){
			map.value = "floorLimitLowReached"
		}else if(descMap.value.toInteger() == 2){
			map.value = "floorLimitMaxReached"
		}else if(descMap.value.toInteger() == 3){
			map.value = "floorAirLimitMaxReached"
		}else{
			map.value = "floorAirLimitMaxReached"
		}

	}
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        if ((map.name.toLowerCase().contains("temp")) || (map.name.toLowerCase().contains("setpoint"))) {
            map.unit = getTemperatureScale()
        }
        result = createEvent(map)
    }
    return result
}
            
//-- Capabilities -----------------------------------------------------------------------------------------

def configure(){    
    if (txtEnable) log.info "configure()"    
    // Set unused default values
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto") // We dont have a fan, so auto it is
    updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device NOT SURE WHAT IT IS...

    try
    {
        unschedule()
    }
    catch (e)
    {
    }
    
	//Set the scheduling some at random moment
    int timesec = Math.abs( new Random().nextInt() % 59) 
    int timemin = Math.abs( new Random().nextInt() % 59)
    int timehour = Math.abs( new Random().nextInt() % 2) 
    schedule(timesec + " " + timemin + " " + timehour + "/3 * * ? *",refreshTime) //refresh the clock at random begining and then every 3h
    
    
    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
	if (tempChange == null)
		tempChange = 50 as int
	if (HeatingChange == null)
		HeatingChange = 5 as int
	if (energyChange == null)
		energyChange = 10 as int
			
	cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 30, 580, (int) tempChange)  //local temperature
	cmds += zigbee.configureReporting(0x0201, 0x0008, 0x0020, 59, 590, (int) HeatingChange) //PI heating demand
	cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 15, 302, 40)   //occupied heating setpoint    
	cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)            //temperature display mode
	cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)            //keypad lockout
	cmds += zigbee.configureReporting(0xFF01, 0x010C, 0x30, 10, 3600, 1) 	// floor limit status each hours

	
	// Configure displayed scale
	if (getTemperatureScale() == 'C') {
		cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)    // Wr °C on thermostat display
	} else {
		cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)    // Wr °F on thermostat display 
	}

	// Configure Outdoor Weather
	if (prefDisplayOutdoorTemp) {
		cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)  //set the outdoor temperature timeout to 3 hours
	} else {
		cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10)  //set the outdoor temperature timeout in 10 seconds, under this value, does not seems to work
	}     

	//Configure Clock Format
	if (prefTimeFormatParam == null)
		prefTimeFormatParam == "24h" as String
	if(prefTimeFormatParam == "12h AM/PM"){//12h AM/PM "24h"
		if(txtEnable) log.info "Set to 12h AM/PM"
		cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001)
	} else {//24h
		if(txtEnable) log.info "Set to 24h"
		cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000)
	}

	//Set the control heating mode
	if (prefAirFloorModeParam == null)
		prefAirFloorModeParam = "Ambient" as String
	if (prefAirFloorModeParam == "Ambient") {//Air mode
		if (txtEnable) log.info "Set to Ambient mode"
		cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0001)
	} else {//Floor mode
		if (txtEnable) log.info "Set to Floor mode"
		cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0002)
	}

	//set the type of sensor
	if (prefFloorSensorTypeParam == null)
		prefFloorSensorTypeParam = "10k" as String
	if (prefFloorSensorTypeParam == "12k") {//sensor type = 12k
		if (prefLogging) log.info "Sensor type is 12k"
		cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0001)
	} else {//sensor type = 10k
		if (prefLogging) log.info "Sensor type is 10k"
		cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0000)
	}
	
	
	//Set temperature limit for floor or air
	if (FloorMaxAirTemperatureParam) {
		def MaxAirTemperatureValue
		if (getTemperatureScale() == 'F') {
			MaxAirTemperatureValue = fahrenheitToCelsius(FloorMaxAirTemperatureParam).toInteger()
		} else { // getTemperatureScale() == 'C'
			MaxAirTemperatureValue = FloorMaxAirTemperatureParam.toInteger()
		}
		
		MaxAirTemperatureValue = Math.min(Math.max(5,MaxAirTemperatureValue),36) //We make sure that it is within the limit
		MaxAirTemperatureValue =  MaxAirTemperatureValue * 100
		cmds += zigbee.writeAttribute(0xFF01, 0x0108, 0x29, MaxAirTemperatureValue)
	}
	else {
		cmds += zigbee.writeAttribute(0xFF01, 0x0108, 0x29, 0x8000)
	}
	
	if (FloorLimitMinParam) {
		def FloorLimitMinValue
		if (getTemperatureScale() == 'F') {
			FloorLimitMinValue = fahrenheitToCelsius(FloorLimitMinParam).toInteger()
		} else { // getTemperatureScale() == 'C'
			FloorLimitMinValue = FloorLimitMinParam.toInteger()
		}
	
		FloorLimitMinValue = Math.min(Math.max(5,FloorLimitMinValue),36) //We make sure that it is within the limit
		FloorLimitMinValue =  FloorLimitMinValue * 100
		cmds += zigbee.writeAttribute(0xFF01, 0x0109, 0x29, FloorLimitMinValue)
	} else {
		cmds += zigbee.writeAttribute(0xFF01, 0x0109, 0x29, 0x8000)
	}

	if (FloorLimitMaxParam) {
		def FloorLimitMaxValue
		if (getTemperatureScale() == 'F') {
			FloorLimitMaxValue = fahrenheitToCelsius(FloorLimitMaxParam).toInteger()
		} else { //getTemperatureScale() == 'C'
			FloorLimitMaxValue = FloorLimitMaxParam.toInteger()
		}
		
		FloorLimitMaxValue = Math.min(Math.max(5,FloorLimitMaxValue),36) //We make sure that it is within the limit
		FloorLimitMaxValue =  FloorLimitMaxValue * 100
		cmds += zigbee.writeAttribute(0xFF01, 0x010A, 0x29, FloorLimitMaxValue)
	}
	else {
		cmds += zigbee.writeAttribute(0xFF01, 0x010A, 0x29, 0x8000)
	}

	if (AuxiliaryCycleLengthParam) {
		switch (AuxiliaryCycleLengthParam) {
			case "1":
			case "15 seconds":
				cmds += zigbee.writeAttribute(0x0201, 0x0404, 0x21, 0x000F)//15 sec
				break
			case "2":
			case "30 minutes":
				cmds += zigbee.writeAttribute(0x0201, 0x0404, 0x21, 0x0708)//30min = 1800sec = 0x708
				break
			case "0":
			case "disable":
			default:
				cmds += zigbee.writeAttribute(0x0201, 0x0404, 0x21, 0x0000)//turn off the auxiliary
				break
		}
	}
	else {
		cmds += zigbee.writeAttribute(0x0201, 0x0404, 0x21, 0x0000)//turn off the auxiliary
	}
	
	if (cmds)
	sendZigbeeCommands(cmds) // Submit zigbee commands
	return
}

def refresh() {
	if (txtEnable) log.info "refresh()"
	runIn(2,"refreshTime")
	def cmds = []
	cmds += zigbee.readAttribute(0x0201, 0x0000) //Read Local Temperature
	cmds += zigbee.readAttribute(0x0201, 0x0008) //Read PI Heating State  
	cmds += zigbee.readAttribute(0x0201, 0x0012) //Read Heat Setpoint
	cmds += zigbee.readAttribute(0x0201, 0x001C) //Read System Mode
	cmds += zigbee.readAttribute(0x0204, 0x0000) //Read Temperature Display Mode
	cmds += zigbee.readAttribute(0x0204, 0x0001) //Read Keypad Lockout

	if (cmds)
		sendZigbeeCommands(cmds) // Submit zigbee commands
	}   

def refreshTemp() {
	def cmds = []
	cmds += zigbee.readAttribute(0x0201, 0x0000)  //Read Local Temperature

	if (cmds)
		sendZigbeeCommands(cmds)
}

def refreshTime() {
	def cmds=[]    
	// Time
	def thermostatDate = new Date();
	def thermostatTimeSec = thermostatDate.getTime() / 1000;
	def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60;
	int currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800); //time from 2000-01-01 00:00
	cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, currentTimeToDisplay, [mfgCode: "0x119C"])

	if (cmds)
		sendZigbeeCommands(cmds)
}

def displayOn() {
	def cmds = []
	cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)

	if (cmds)
		sendZigbeeCommands(cmds) // Submit zigbee commands
}

def displayOff() {
	def cmds = []
	cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)

	if (cmds)
		sendZigbeeCommands(cmds) // Submit zigbee commands
}

def auto() {
	log.warn "auto(): mode is not available for this device. => Defaulting to heat mode instead."
	heat()
}

def cool() {
	log.warn "cool(): mode is not available for this device. => Defaulting to heat mode instead."
	heat()
}

def emergencyHeat() {
	log.warn "emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead."
	heat()
}

def fanAuto() {
	log.warn "fanAuto(): mode is not available for this device"
}

def fanCirculate() {
	log.warn "fanCirculate(): mode is not available for this device"
}

def fanOn() {
	log.warn "fanOn(): mode is not available for this device"
}

def heat() {
	if (txtEnable) log.info "heat(): mode set"

	def cmds = []
	cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
	cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 04, [mfgCode: "0x1185"]) // SETPOINT MODE
	cmds += zigbee.readAttribute(0x0201, 0x001C)
	
	// Submit zigbee commands
	sendZigbeeCommands(cmds)
}

def off() {
	if (txtEnable) log.info "off(): mode set"

	def cmds = []
	cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
	//cmds += zigbee.readAttribute(0x0201, 0x0008)
	cmds += zigbee.readAttribute(0x0201, 0x001C)

	// Submit zigbee commands
	sendZigbeeCommands(cmds)    
	}

def setCoolingSetpoint(degrees) {
    log.warn "setCoolingSetpoint(${degrees}): is not available for this device"
}

def setHeatingSetpoint(preciseDegrees) {
	if (preciseDegrees != null) {
		unschedule("Setpoint")
		state.setPoint = preciseDegrees
		runIn(2,"Setpoint")
	}
}

def Setpoint() {
    if (state.setPoint != device.currentValue("heatingSetpoint")) {
        runIn(30, "Setpoint")
        def temperatureScale = getTemperatureScale()
        def degrees = state.setPoint
        def cmds = []        

        if (txtEnable) log.info "setHeatingSetpoint(${degrees}:${temperatureScale})"

        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)

        cmds += zigbee.writeAttribute(0x0201, 0x0012, 0x29, celsius100) //Write Heat Setpoint

        // Submit zigbee commands
        sendZigbeeCommands(cmds)
    }
}

def setThermostatFanMode(fanmode){
    log.warn "setThermostatFanMode(${fanmode}): is not available for this device"
}

def setThermostatMode(String value) {
    if (txtEnable) log.info "setThermostatMode(${value})"
    
    switch (value) {
        case "heat":
        case "emergency heat":
        case "auto":
        case "cool":
            return heat()
        
        case "off":
            return off()
    }
}

def unlock() {
  if (txtEnable) log.info "TH1123ZB >> unlock()"
  sendEvent(name: "lock", value: "unlocked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)

  sendZigbeeCommands(cmds)
}

def lock() {
  if (txtEnable) log.info "TH1123ZB >> lock()"
  sendEvent(name: "lock", value: "locked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)

  sendZigbeeCommands(cmds)
}

def deviceNotification(text) {
	double outdoorTemp = text.toDouble()
	def cmds = []

	if (prefDisplayOutdoorTemp) {
		if (txtEnable) log.info "deviceNotification() : Received outdoor weather : ${text} : ${outdoorTemp}"

		sendEvent(name: "outdoorTemp", value: outdoorTemp, unit: getTemperatureScale())
		//the value sent to the thermostat must be in C
		if (getTemperatureScale() == 'F') {    
			outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
		}        

		int outdoorTempDevice = outdoorTemp*100
		cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)   //set the outdoor temperature timeout to 3 hours
		cmds += zigbee.writeAttribute(0xFF01, 0x0010, 0x29, outdoorTempDevice, [mfgCode: "0x119C"]) //set the outdoor temperature as integer

		// Submit zigbee commands    
		sendZigbeeCommands(cmds)
	} else {
		if (txtEnable) log.info "deviceNotification() : Not setting any outdoor weather, since feature is disabled."  
    }
}

def removeOldStateVariable(text) {
	state.remove(text)
}

//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getTemperature(value) {
    if (value != null) {
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

private getModeMap() {
  [
    "00": "off",
    "04": "heat"
  ]
}

private getLockMap() {
  [
    "00": "unlocked ",
    "01": "locked ",
  ]
}

private getTemperatureScale() {
    return "${location.temperatureScale}"
}

private getHeatingDemand(value) {
    if (value != null) {
        def demand = Integer.parseInt(value, 16)
        return demand.toString()
    }
}

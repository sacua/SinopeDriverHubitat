/**
 *  Sinope TH1123ZB, TH1124ZB Device Driver for Hubitat
 *  Source: https://raw.githubusercontent.com/sacua/SinopeDriverHubitat/main/drivers/TH112xZB_Sinope_Hubitat.groovy
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements and from the driver of scoulombe79 and kris2k2
 *  Source: https://support.sinopetech.com/en/wp-content/uploads/sites/4/2019/08/Sinope-Technologies-Zigbee-Thermostat-V.1.0.0-SVN-547-1.txt
 *  Source: https://github.com/scoulombe79/Hubitat/blob/master/Drivers/Thermostat-Sinope-TH1123ZB.groovy
 *  Source: https://github.com/kris2k2/hubitat/blob/master/drivers/kris2k2-Sinope-TH112XZB.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * v1.0.0 Initial commit
 * v1.1.0 Dont remember
 * v1.2.0 Correction for attribute reading for heat and off command
 * v1.3.0 Correction for the offset calculation very rarely, the reading is a super large negative value, when that happen, the offset does not change
 * v1.4.0 Enable debug parse, send event at configure for the supported mode and possible to reset the offset value
 * v1.5.0 Enable custom time for reset and manual reset
 * v1.5.1 Correction of bug for the reset of energy meter
 * V2.0.0 Use library and add digital temperature offsett
 */

#include sacua.EnergyLibrary

metadata {
	definition(name: "Thermostat TH112xZB with energy meter - Test", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
		capability "Thermostat"
		capability "Configuration"
		capability "TemperatureMeasurement"
		capability "Refresh"
		capability "Lock"
		capability "PowerMeter"
		capability "EnergyMeter"
		capability "Notification" // Receiving temperature notifications via RuleEngine

		attribute "outdoorTemp", "number"
		attribute "heatingDemand", "number"
		attribute "cost", "number"
		attribute "dailyCost", "number"
		attribute "weeklyCost", "number"
		attribute "monthlyCost", "number"
		attribute "yearlyCost", "number"
		attribute "dailyEnergy", "number"
		attribute "weeklyEnergy", "number"
		attribute "monthlyEnergy", "number"
		attribute "yearlyEnergy", "number"
		attribute "maxPower", "number"

		command "refreshTime" //Refresh the clock on the thermostat
		command "displayOn"
		command "displayOff"
		command "refreshTemp" //To refresh only the temperature reading
		command "resetEnergyOffset", ["number"]
		command "resetDailyEnergy"
		command "resetWeeklyEnergy"
		command "resetMonthlyEnergy"
		command "resetYearlyEnergy"

		preferences {
			input name: "prefDisplayOutdoorTemp", type:"bool", title: "Display outdoor temperature", defaultValue: true
			input name: "prefTimeFormatParam", type: "enum", title: "Time Format", options:["24h", "12h AM/PM"], defaultValue: "24h", multiple: false, required: true
			input name: "tempChange", type: "number", title: "Temperature change", description: "Minumum change of temperature reading to trigger report in Celsius/100, 5..50", range: "5..50", defaultValue: 50
			input name: "HeatingChange", type: "number", title: "Heating change", description: "Minimum change in the PI heating in % to trigger power and PI heating reporting, 1..25", range: "1..25", defaultValue: 5
			input name: "energyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting, 10..*", range: "10..*", defaultValue: 10
			input name: "energyPrice", type: "float", title: "c/kWh Cost:", description: "Electric Cost per Kwh in cent", range: "0..*", defaultValue: 9.38
			input name: "tempOffset", type: "float", title: "Digital temperature offset", description: "Digital temperature offset for reading in Celsius", defaultValue: 0
			input name: "weeklyReset", type: "enum", title: "Weekly reset day", description: "Day on which the weekly energy meter return to 0", options:["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"], defaultValue: "Sunday", multiple: false, required: true
			input name: "yearlyReset", type: "enum", title: "Yearly reset month", description: "Month on which the yearly energy meter return to 0", options:["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"], defaultValue: "January", multiple: false, required: true
			input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
		}

		fingerprint profileId: "0104", deviceId: "119C", manufacturer: "Sinope Technologies", model: "TH1123ZB", deviceJoinName: "TH1123ZB"
		fingerprint profileId: "0104", deviceId: "119C", manufacturer: "Sinope Technologies", model: "TH1124ZB", deviceJoinName: "TH1124ZB"

	}
}

//-- Parsing -----------------------------------------------------------------------------------------
private createCustomMap(descMap){
	def result = null
	def map = [: ]

	if (descMap.cluster == "0201") {
		switch(descMap.attrId) {
			case "0000":
				map.name = "temperature"
				map.value = getTemperature(descMap.value)
				map.unit = getTemperatureScale()
				map.descriptionText = "The tempeature is ${map.value} ${map.unit}"
				break

			case "0008":
				map.name = "thermostatOperatingState"
				map.value = getHeatingDemand(descMap.value)
				sendEvent(name: "heatingDemand", value: map.value, unit: "%", descriptionText: "The thermostat is heating at ${map.value}% of its capacity")
				if (device.currentValue("maxPower") != null) {
					def maxPowerValue = device.currentValue("maxPower").toInteger()
					def powerValue = Math.round(maxPowerValue*map.value/100)
					sendEvent(name: "power", value: powerValue, unit: "W", descriptionText: "The current heating power is ${powerValue} Watts") //The power is not auto reported
				}
				map.value = (map.value < 5) ? "idle" : "heating"
				break

			case "0012":
				map.name = "heatingSetpoint"
				map.value = getTemperature(descMap.value)
				map.unit = getTemperatureScale()
				map.descriptionText = "The hearing setpoint is ${map.value} ${map.unit}"
				sendEvent(name: "thermostatSetpoint", value: map.value, unit: getTemperatureScale()) //For interoperability with SharpTools
				break

			case "001C":
				map.name = "thermostatMode"
				map.value = getModeMap()[descMap.value]
				break

			default: // unused
				//logDebug("unhandled electrical measurement attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
				break
		}

	} else if (descMap.cluster == "0204" && descMap.attrId == "0001") {
		map.name = "lock"
		map.value = getLockMap()[descMap.value]

	} else if (descMap.cluster == "0B04") {
		switch(descMap.attrId) {
			case "050B":
				map.name = "power"
				map.value = getActivePower(descMap.value)
				map.unit = "W"
				map.descriptionText = "The current heating power is ${powerValue} Watts"
				break

			case "050D":
				map.name = "maxPower"
				map.value = getActivePower(descMap.value)
				map.unit = "W"
				map.descriptionText = "The maximum heating power is ${powerValue} Watts"
				break

			default: // unused
				//logDebug("unhandled electrical measurement attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
				break
		}

	} else if (descMap.cluster == "0702" && descMap.attrId == "0000") {
		state.energyValue = getEnergy(descMap.value) as BigInteger
		runIn(2,"energyCalculation")
	}

	if (map) {
		def isChange = isStateChange(device, map.name, map.value.toString())
		map.displayed = isChange
		logDebug("event map : ${map}")
		if (map.descriptionText) logInfo("${map.descriptionText}")
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
	updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device
	sendEvent(name: "supportedThermostatModes", value:  "[\"off\", \"heat\"]") //We set the supported thermostat mode
	sendEvent(name: "supportedThermostatFanModes", value:  "[\"auto\"]") //We set the supported thermostat mode

	try
	{
		unschedule()
	}
	catch (e)
	{
	}

	int timesec = Math.abs( new Random().nextInt() % 59)
	int timemin = Math.abs( new Random().nextInt() % 59)
	int timehour = Math.abs( new Random().nextInt() % 2)
	schedule(timesec + " " + timemin + " " + timehour + "/3 * * ? *",refreshTime) //refresh the clock at random begining and then every 3h
	schedule("0 0 * * * ? *", energySecCalculation)
	schedule("0 0 0 * * ? *", resetDailyEnergy)
	schedule("0 0 0 1 * ? *", resetMonthlyEnergy)
	timesec = Math.abs( new Random().nextInt() % 59)
	timemin = Math.abs( new Random().nextInt() % 59)
	timehour = Math.abs( new Random().nextInt() % 23)
	schedule(timesec + " " + timemin + " " + timehour + " * * ? *", refreshMaxPower) //refresh maximum power capacity of the equipement wired to the thermostat one time per day at a random moment

	energySchedule()

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
	cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) energyChange) //Energy reading
	cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 15, 302, 40)   //occupied heating setpoint
	cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)			//temperature display mode
	cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)			//keypad lockout


	// Configure displayed scale
	if (getTemperatureScale() == 'C') {
		cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)	// Wr °C on thermostat display
	} else {
		cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)	// Wr °F on thermostat display
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
	if (prefTimeFormatParam == "12h AM/PM") {//12h AM/PM "24h"
		if(txtEnable) log.info "Set to 12h AM/PM"
		cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001)
	} else {//24h
		if(txtEnable) log.info "Set to 24h"
		cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000)
	}

	if (cmds)
		sendZigbeeCommands(cmds) // Submit zigbee commands
	return
}

def refresh() {
	if (txtEnable) log.info "refresh()"
	runIn(2,"refreshTime")
	def cmds = []
	cmds += zigbee.readAttribute(0x0B04, 0x050D) //Read highest power delivered
	cmds += zigbee.readAttribute(0x0201, 0x0000) //Read Local Temperature
	cmds += zigbee.readAttribute(0x0201, 0x0008) //Read PI Heating State
	cmds += zigbee.readAttribute(0x0201, 0x0012) //Read Heat Setpoint
	cmds += zigbee.readAttribute(0x0201, 0x001C) //Read System Mode
	cmds += zigbee.readAttribute(0x0204, 0x0000) //Read Temperature Display Mode
	cmds += zigbee.readAttribute(0x0204, 0x0001) //Read Keypad Lockout
	cmds += zigbee.readAttribute(0x0702, 0x0000) //Read energy delivered

	if (cmds)
		sendZigbeeCommands(cmds) // Submit zigbee commands
}

def refreshTemp() {
	def cmds = []
	cmds += zigbee.readAttribute(0x0201, 0x0000)  //Read Local Temperature

	if (cmds)
		sendZigbeeCommands(cmds)
}

def refreshMaxPower() {
	def cmds = []
	cmds += zigbee.readAttribute(0x0B04, 0x050D)  //Read highest power delivered

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
	cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 4)
	//cmds += zigbee.readAttribute(0x0201, 0x0008)
	cmds += zigbee.readAttribute(0x0201, 0x001C)

	// Submit zigbee commands
	sendZigbeeCommands(cmds)
}

def off() {
	if (txtEnable) log.info "off(): mode set, it means no heating!"

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
		def degrees = state.setPoint - tempOffset
		def cmds = []

		if (txtEnable) log.info "setHeatingSetpoint(${state.setPoint}:${temperatureScale})"

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
	if (text != null) {
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
}

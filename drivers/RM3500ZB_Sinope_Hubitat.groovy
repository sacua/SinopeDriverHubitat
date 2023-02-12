/**
 *  Sinope RM3500ZB Water heater controller Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/RM3500ZB_Sinope_Hubitat.groovy
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
 */

metadata
{
    definition(name: "Water heater controller RM3500ZB with energy meter", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "TemperatureMeasurement"
        capability "WaterSensor"
         
        attribute "cost", "number"
        attribute "dailyCost", "number"
        attribute "weeklyCost", "number"
        attribute "monthlyCost", "number"
        attribute "yearlyCost", "number"
        attribute "dailyEnergy", "number"
        attribute "weeklyEnergy", "number"
        attribute "monthlyEnergy", "number"
        attribute "yearlyEnergy", "number"
        
        command "resetEnergyOffset", ["number"]
        command "resetDailyEnergy"
        command "resetWeeklyEnergy"
        command "resetMonthlyEnergy"
        command "resetYearlyEnergy"
  
	    fingerprint inClusters: "0000,0002,0003,0004,0005,0006,0402,0500,0702,0B04,0B05,FF01", outClusters: "000A,0019", manufacturer: "Sinope Technologies", model: "RM3500ZB", deviceJoinName: "Sinope Calypso Smart Water Heater Controller"
    }     
         
    preferences {
        input name: "tempChange", type: "number", title: "Temperature change", description: "Minumum change of temperature reading to trigger report in Celsius/100, 10..200", range: "10..200", defaultValue: 100
        input name: "PowerReport", type: "number", title: "Power change", description: "Amount of wattage difference to trigger power report (1..*)",  range: "1..*", defaultValue: 30
        input name: "energyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting (10..*)", range: "10..*", defaultValue: 10
        input name: "energyPrice", type: "float", title: "c/kWh Cost:", description: "Electric Cost per Kwh in cent", range: "0..*", defaultValue: 9.38
        input name: "weeklyReset", type: "enum", title: "Weekly reset day", description: "Day on which the weekly energy meter return to 0", options:["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"], defaultValue: "Sunday", multiple: false, required: true
        input name: "yearlyReset", type: "enum", title: "Yearly reset month", description: "Month on which the yearly energy meter return to 0", options:["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"], defaultValue: "January", multiple: false, required: true
        //input name: "prefWaterTempMin", type: "bool", title: "Enable safety minimum water temperature 45°C/113°F feature", defaultValue: true
        input name: "infoEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
    
}

//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    if (infoEnable) log.info "installed() : running configure()"
    if (state.time == null)  
      state.time = now()
    if (state.energyValue == null) 
      state.energyValue = 0 as double
    if (state.costValue == null) 
      state.costValue = 0 as float
    if (state.powerValue == null)  
      state.powerValue = 0 as int
    configure()
}

def updated() {
    if (infoEnable) log.info "updated() : running configure()"
    
    if (state.time == null)  
      state.time = now()
    if (state.energyValue == null) 
      state.energyValue = 0 as double
    if (state.powerValue == null)  
      state.powerValue = 0 as int
    if (state.costValue == null) 
      state.costValue = 0 as float
    
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
      state.updatedLastRanAt = now()
      configure()
      refresh()
   }
}

def uninstalled() {
    if (infoEnable) log.info "uninstalled() : unscheduling configure() and reset()"
    try {    
        unschedule()
    } catch (errMsg) {
        log.info "uninstalled(): Error unschedule() - ${errMsg}"
    }
}

      
//-- Parsing -----------------------------------------------------------------------------------------

// parse events into attributes
def parse(String description) {
    if (debugEnable) log.debug "parse - description = ${description}"
    def result = []
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
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
        if (descMap.cluster == "0006" && descMap.attrId == "0000") {
            map.name = "switch"
            map.value = getSwitchStatus(descMap.value)
            //map.value = getSwitchMap()[descMap.value]   // Changed method so that we can use log.info since this device has a physical switch on the device
            
        } else if (descMap.cluster == "0B04" && descMap.attrId == "050B") {
            map.name = "power"
            map.value = getActivePower(descMap.value)
            map.unit = "W"
        
        } else if (descMap.cluster == "0702" && descMap.attrId == "0000") {
            state.energyValue = getEnergy(descMap.value) as BigInteger
            runIn(2,"energyCalculation")
            
        } else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
		    map.name = "temperature"
		    map.value = getTemperature(descMap.value)
        
        } else if (descMap.cluster == "0500" && descMap.attrId == "0002") {
            map.name = "water"
		    log.debug "water sensor change detected : " + descMap.value
            map.value = getWaterStatus(descMap.value)
        }

        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        result = createEvent(map)
    }
    return result
}
            
//-- Capabilities -----------------------------------------------------------------------------------------

def configure(){    
    if (infoEnable) log.info "configure()"    
    try
    {
        unschedule()
    }
    catch (e)
    {
    }
    
    schedule("0 0 * * * ? *", energySecCalculation)
    schedule("0 0 0 * * ? *", resetDailyEnergy)
    schedule("0 0 0 1 * ? *", resetMonthlyEnergy)
    
     if (weeklyReset == null)
		weeklyReset = "Sunday" as String
    if (yearlyReset == null)
		yearlyReset = "January" as String
    
    if (yearlyReset == "January") {
        schedule("0 0 0 1 1 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 2 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "March") {
        schedule("0 0 0 1 3 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "April") {
        schedule("0 0 0 1 4 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "May") {
        schedule("0 0 0 1 5 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 6 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 7 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 8 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 9 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 10 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 11 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 12 ? *", resetYearlyEnergy)
    }
    
    if (weeklyReset == "Sunday") {
        schedule("0 0 0 ? * 1 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Monday") {
        schedule("0 0 0 ? * 2 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Tuesday") {
        schedule("0 0 0 ? * 3 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Wednesday") {
        schedule("0 0 0 ? * 4 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Thursday") {
        schedule("0 0 0 ? * 5 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Friday") {
        schedule("0 0 0 ? * 6 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Saturday") {
        schedule("0 0 0 ? * 7 *", resetWeeklyEnergy)
    }

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0402, 0x0000, 0x29, 30, 580, (int) tempChange)  //local temperature
    cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 0, 600, null)
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null)           //On off state
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) PowerReport)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 299, 1799, (int) energyChange) //Energy reading
    
	// Configure Water Temp Min
	if (prefWaterTempMin) {
		cmds += zigbee.writeAttribute(0xFF01, 0x0076, 0x21, 45)  //set water temp min to 45 (only acceptable value)
	} else {
		cmds += zigbee.writeAttribute(0xFF01, 0x0076, 0x21, 0)  //set water temp min to 0 (disabled)
	}

    
    if (cmds)
      sendZigbeeCommands(cmds) // Submit zigbee commands
    return
}

def refresh() {
    if (infoEnable) log.info "refresh()"
    
    def cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x0500, 0x0002) //Read Water leak
    cmds += zigbee.readAttribute(0xFF01, 0x0076) //Read state of water temp safety setting (45 or 0) 
    cmds += zigbee.readAttribute(0x0006, 0x0000) //Read On off state
    cmds += zigbee.readAttribute(0x0B04, 0x050B)  //Read thermostat Active power
    cmds += zigbee.readAttribute(0x0702, 0x0000) //Read energy delivered
    
    if (cmds)
        sendZigbeeCommands(cmds) // Submit zigbee commands
}   


def off() {
    state.flashing = false
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)    
}

def on() {
    state.flashing = false
    def cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)    
}

def resetEnergyOffset(text) {
     if (text != null) {
          BigInteger newOffset = text.toBigInteger()
          state.dailyEnergy = state.dailyEnergy - state.offsetEnergy + newOffset
          state.weeklyEnergy = state.weeklyEnergy - state.offsetEnergy + newOffset
          state.monthlyEnergy = state.monthlyEnergy - state.offsetEnergy + newOffset
          state.yearlyEnergy = state.yearlyEnergy - state.offsetEnergy + newOffset
          state.offsetEnergy = newOffset
          float totalEnergy = (state.energyValue + state.offsetEnergy)/1000
          sendEvent(name: "energy", value: totalEnergy, unit: "kWh")
          runIn(2,energyCalculation)
          runIn(2,energySecCalculation)
     }
}

def energyCalculation() {
    if (state.offsetEnergy == null)
        state.offsetEnergy = 0 as BigInteger
    if (state.dailyEnergy == null)
       state.dailyEnergy = state.energyValue as BigInteger
    if (device.currentValue("energy") == null)
        sendEvent(name: "energy", value: 0, unit: "kWh")
     
    if (state.energyValue + state.offsetEnergy < device.currentValue("energy")*1000) { //Although energy are parse as BigInteger, sometimes (like 1 times per month during heating  time) the value received is lower than the precedent but not zero..., so we define a new offset when that happen
        BigInteger newOffset = device.currentValue("energy")*1000 - state.energyValue as BigInteger
        if (newOffset < 1e10) //Sometimes when the hub boot, the offset is very large... munch too large
            state.offsetEnergy = newOffset
    }
    
    float dailyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.dailyEnergy)/1000)
    float totalEnergy = (state.energyValue + state.offsetEnergy)/1000
    
    localCostPerKwh = energyPrice as float
    float dailyCost = roundTwoPlaces(dailyEnergy*localCostPerKwh/100)
    
    sendEvent(name: "dailyEnergy", value: dailyEnergy, unit: "kWh")
    sendEvent(name: "dailyCost", value: dailyCost, unit: "\$")
    sendEvent(name: "energy", value: totalEnergy, unit: "kWh")
}

def energySecCalculation() { //This one is performed every hour to not overwhelm the number of events which will create a warning in hubitat main page
    if (state.weeklyEnergy == null)
       state.weeklyEnergy = state.energyValue as BigInteger
    if (state.monthlyEnergy == null)
       state.monthlyEnergy = state.energyValue as BigInteger
    if (state.yearlyEnergy == null)
       state.yearlyEnergy = state.energyValue as BigInteger
    
    float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy)/1000)
    float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy)/1000)
    float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy)/1000)
    float totalEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy)/1000)
    
    localCostPerKwh = energyPrice as float
    float weeklyCost = roundTwoPlaces(weeklyEnergy*localCostPerKwh/100)
    float monthlyCost = roundTwoPlaces(monthlyEnergy*localCostPerKwh/100)
    float yearlyCost = roundTwoPlaces(yearlyEnergy*localCostPerKwh/100)
    float totalCost = roundTwoPlaces(totalEnergy*localCostPerKwh/100)

    sendEvent(name: "weeklyEnergy", value: weeklyEnergy, unit: "kWh")
    sendEvent(name: "monthlyEnergy", value: monthlyEnergy, unit: "kWh")
    sendEvent(name: "yearlyEnergy", value: yearlyEnergy, unit: "kWh")

    sendEvent(name: "weeklyCost", value: weeklyCost, unit: "\$")
    sendEvent(name: "monthlyCost", value: monthlyCost, unit: "\$")
    sendEvent(name: "yearlyCost", value: yearlyCost, unit: "\$")
    sendEvent(name: "cost", value: totalCost, unit: "\$")      
    
}

def resetDailyEnergy() {
	state.dailyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float dailyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.dailyEnergy)/1000)
	float dailyCost = roundTwoPlaces(dailyEnergy*localCostPerKwh/100)
    sendEvent(name: "dailyEnergy", value: dailyEnergy, unit: "kWh")
	sendEvent(name: "dailyCost", value: dailyCost, unit: "\$")
    
}

def resetWeeklyEnergy() {
	state.weeklyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy)/1000)
	float weeklyCost = roundTwoPlaces(weeklyEnergy*localCostPerKwh/100)
    sendEvent(name: "weeklyEnergy", value: weeklyEnergy, unit: "kWh")
	sendEvent(name: "weeklyCost", value: weeklyCost, unit: "\$")
}

def resetMonthlyEnergy() {
	state.monthlyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy)/1000)
	float monthlyCost = roundTwoPlaces(monthlyEnergy*localCostPerKwh/100)
    sendEvent(name: "monthlyEnergy", value: monthlyEnergy, unit: "kWh")
	sendEvent(name: "monthlyCost", value: monthlyCost, unit: "\$")
}

def resetYearlyEnergy() {
	state.yearlyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy)/1000)
	float yearlyCost = roundTwoPlaces(yearlyEnergy*localCostPerKwh/100)
    sendEvent(name: "yearlyEnergy", value: yearlyEnergy, unit: "kWh")
	sendEvent(name: "yearlyCost", value: yearlyCost, unit: "\$")
}
                  
//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    //cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getTemperature(value) {
    // First test if temp sensor connected to device
    if (value == "8000") {
        return 0 
    }
    
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

private getWaterStatus(value) {
    switch(value) {
        case "0030" :
            if (infoEnable) log.info "Water sensor dry"
            return "dry"
            break
        case "0031" :
            if (infoEnable) log.info "Water sensor wet"
            return "wet"
            break
    }
}

private getSwitchStatus(value) {
    switch(value) {
        case "00" :
            if (infoEnable) log.info "Switch was turned off"
            return "off"
            break
        case "01" :
            if (infoEnable) log.info "Switch was turned on"
            return "on"
            break
    }
}

private getSwitchMap() {
  [
    "00": "off",
    "01": "on",
  ]
}

private getActivePower(value) {
  if (value != null)
  {
    def activePower = Integer.parseInt(value, 16)
    return activePower
  }
}


private roundTwoPlaces(val)
{
  return Math.round(val * 100) / 100
}

private hex(value)
{
  String hex = new BigInteger(Math.round(value).toString()).toString(16)
  return hex
}

private getEnergy(value) {
  if (value != null)
  {
    BigInteger EnergySum = new BigInteger(value,16)
    return EnergySum
  }
}

private getTemperatureScale() {
	return "${location.temperatureScale}"
}

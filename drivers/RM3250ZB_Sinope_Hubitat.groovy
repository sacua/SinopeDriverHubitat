/**
 *  Sinope RM3250ZB Load control Device Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/RM3250ZB_Sinope_Hubitat.groovy
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
 */

metadata
{
     definition(name: "Load Control Sinope", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Flash"
         
        attribute "cost", "number"
        attribute "dailyCost", "number"
        attribute "weeklyCost", "number"
        attribute "monthlyCost", "number"
        attribute "yearlyCost", "number"
        attribute "dailyEnergy", "number"
        attribute "weeklyEnergy", "number"
        attribute "monthlyEnergy", "number"
        attribute "yearlyEnergy", "number"
        
        preferences {
          input name: "PowerReport", type: "number", title: "Power change", description: "Amount of wattage difference to trigger power report (1..*)",  range: "1..*", defaultValue: 30
          input name: "energyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting (10..*)", range: "10..*", defaultValue: 10
          input name: "Flashrate", type: "float", title: "Default flash rate in ms (250..*)", range: "250..*", defaultValue: 750
          input name: "energyPrice", type: "float", title: "c/kWh Cost:", description: "Electric Cost per Kwh in cent", range: "0..*", defaultValue: 9.38
          input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
        }
        
    }
}

//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    if (txtEnable) log.info "installed() : running configure()"
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
    if (txtEnable) log.info "updated() : running configure()"
    
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
        if (descMap.cluster == "0006" && descMap.attrId == "0000" && state.flashing == false) {
            map.name = "switch"
            map.value = getSwitchMap()[descMap.value]
            
        } else if (descMap.cluster == "0B04" && descMap.attrId == "050B") {
            map.name = "power"
            map.value = getActivePower(descMap.value)
            map.unit = "W"
        
        } else if (descMap.cluster == "0702" && descMap.attrId == "0000") {
            state.energyValue = getEnergy(descMap.value) as BigInteger
            runIn(2,"energyCalculation")
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
    if (txtEnable) log.info "configure()"    
    try
    {
        unschedule()
    }
    catch (e)
    {
    }
    
    schedule("0 0 * * * ? *", energySecCalculation)
    schedule("0 0 0 * * ? *", dailyEnergy)
    schedule("0 0 0 ? * 1 *", weeklyEnergy)
    schedule("0 0 0 1 * ? *", monthlyEnergy)
    schedule("0 0 0 1 1 ? *", yearlyEnergy)
    
    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null)           //On off state
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) PowerReport)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) energyChange) //Energy reading
    
    if (cmds)
      sendZigbeeCommands(cmds) // Submit zigbee commands
    return
}

def refresh() {
    if (txtEnable) log.info "refresh()"
    
    def cmds = []    
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

def offFlash() {
    if (state.flashing) sendZigbeeCommands([] + zigbee.command(0x0006, 0x00))
}

def flashOnOff(rateToFlash) {
    if (state.flashing) {
        sendZigbeeCommands([] + zigbee.command(0x0006, 0x01))
        runInMillis(rateToFlash,"offFlash")
        runInMillis(rateToFlash+rateToFlash,"flashOnOff",[data: rateToFlash])
    }
}

def flash(rateToFlash) {
    state.flashing = true
    if (rateToFlash == null) {
        Flashrate = Flashrate as float
        rateToFlash = Flashrate/2 as Long
    } else {
        rateToFlash = rateToFlash/2 as Long
    }
    flashOnOff(rateToFlash)
}

def energyCalculation() {
    if (state.offsetEnergy == null)
        state.offsetEnergy = 0 as BigInteger
    if (state.dailyEnergy == null)
       state.dailyEnergy = state.energyValue as BigInteger
    if (device.currentValue("energy") == null)
        sendEvent(name: "energy", value: 0, unit: "kWh")
    
    if (state.energyValue + state.offsetEnergy < device.currentValue("energy")*1000) //Although energy are parse as BigInteger, sometimes (like 1 times per month during heating  time) the value received is lower than the precedent but not zero..., so we define a new offset when that happen
        state.offsetEnergy = device.currentValue("energy")*1000 - state.energyValue as BigInteger
    
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

def dailyEnergy() {
   state.dailyEnergy = state.energyValue + state.offsetEnergy
}

def weeklyEnergy() {
   state.weeklyEnergy = state.energyValue + state.offsetEnergy
}

def monthlyEnergy() {
   state.monthlyEnergy = state.energyValue + state.offsetEnergy
}

def yearlyEnergy() {
   state.yearlyEnergy = state.energyValue + state.offsetEnergy
}
                  
//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    //cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
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

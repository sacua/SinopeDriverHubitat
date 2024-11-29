/**
 *  Sinope TH1123ZB, TH1124ZB Device Driver for Hubitat
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
 * v1.0.0 Initial commit
 * v1.1.0 Correction for the offset calculation very rarely, the reading is a super large negative value, when that happen, the offset does not change
 * v1.2.0 Enable debug parse and possible to reset the offset value
 * v1.3.0 Enable custom time for reset and manual reset
 * v1.3.1 Correction of bug for the reset of energy meter
 * v2.0.0 Major code cleaning - Pseudo library being used - new capabilities added (2024-11-28)
 */

metadata
{
     definition(name: "Switch SP2600ZB with energy meter", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "TemperatureMeasurement"
        capability "VoltageMeasurement"
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
        
        command "resetEnergyOffset", ["number"]
        command "resetDailyEnergy"
        command "resetWeeklyEnergy"
        command "resetMonthlyEnergy"
        command "resetYearlyEnergy"

        fingerprint profileId:"0104", inClusters:"0000,0002,0003,0004,0005,0006,0702,0B04,0B05,FF01", outClusters:"0003,0004,0019", model:"SP2600ZB", manufacturer:"Sinope Technologies", deviceJoinName: "Sinope outlet"
    }

    preferences {
        input name: "powerReport", type: "number", title: "Power change", description: "Amount of wattage difference to trigger power report (1..*)",  range: "1..*", defaultValue: 30
        input name: "energyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting (10..*)", range: "10..*", defaultValue: 10
        input name: "Flashrate", type: "float", title: "Default flash rate in ms (250..*)", range: "250..*", defaultValue: 750
        input name: "energyPrice", type: "float", title: "c/kWh Cost:", description: "Electric Cost per kWh in cent", range: "0..*", defaultValue: 9.38
        input name: "weeklyReset", type: "enum", title: "Weekly reset day", description: "Day on which the weekly energy meter return to 0", options:["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"], defaultValue: "Sunday", multiple: false, required: true
        input name: "yearlyReset", type: "enum", title: "Yearly reset month", description: "Month on which the yearly energy meter return to 0", options:["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"], defaultValue: "January", multiple: false, required: true
        input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}
            
//-- Capabilities -----------------------------------------------------------------------------------------

def configure(){    
    logInfo("configure()")  
    try
    {
        unschedule()
    }
    catch (e)
    {
    }
    state.powerDivider = 10 as Float

    energyScheduling()

    state.switchTypeDigital = false

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null)                               // On off state
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x29, 30, 600)                                    // Voltage
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x29, 30, 600, (int) powerReport*4)               // Current
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) powerReport)                 // Power report
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) energyChange)    // Energy reading
    
    if (cmds)
      sendZigbeeCommands(cmds) // Submit zigbee commands
    return
}

def refresh() {
    logInfo("refresh()")
    
    def cmds = []    
    cmds += zigbee.readAttribute(0x0006, 0x0000)    // Read On off state
    cmds += zigbee.readAttribute(0x0702, 0x0000)    // Read energy delivered
    cmds += zigbee.readAttribute(0x0B04, 0x050B)    // Read thermostat Active power
    cmds += zigbee.readAttribute(0x0B04, 0x0505)    // Read voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508)    // Read amperage
    
    if (cmds)
        sendZigbeeCommands(cmds) // Submit zigbee commands
}   


def off() {
    state.flashing = false
    state.switchTypeDigital = true
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)    
}

def on() {
    state.flashing = false
    state.switchTypeDigital = true
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
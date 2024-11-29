/**
 *  Sinope RM3250ZB Load control Device Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/mergeDrivers/RM3250ZB_Sinope_Hubitat.groovy
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
 * v1.1.0 Correction for the offset calculation very rarely, the reading is a super large negative value, when that happen, the offset does not change and remove flash capability
 * v1.2.0 Enable debug parse and possible to reset the offset value
 * v1.3.0 Enable custom time for reset and manual reset
 * v1.4.0 Correct typo mistake in Cron Scheduling
 * V1.5.0 Consistency between driver from RM3500ZB
 * v2.0.0 Major code cleaning - Pseudo library being used (2024-11-28)
 */

metadata
{
    definition(name: 'Load Controller RM3250ZB with energy meter', namespace: 'sacua', author: 'Samuel Cuerrier Auclair') {
        capability 'Switch'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Outlet'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'CurrentMeter'
        capability 'VoltageMeasurement'

        attribute 'cost', 'number'
        attribute 'dailyCost', 'number'
        attribute 'weeklyCost', 'number'
        attribute 'monthlyCost', 'number'
        attribute 'yearlyCost', 'number'
        attribute 'dailyEnergy', 'number'
        attribute 'weeklyEnergy', 'number'
        attribute 'monthlyEnergy', 'number'
        attribute 'yearlyEnergy', 'number'

        command 'resetEnergyOffset', ['number']
        command 'resetDailyEnergy'
        command 'resetWeeklyEnergy'
        command 'resetMonthlyEnergy'
        command 'resetYearlyEnergy'

        fingerprint profileId:'0104', inClusters:'0000,0002,0003,0004,0005,0006,0702,0B04,0B05,FF01', outClusters:'0003,0004,0019', model:'RM3250ZB', manufacturer:'Sinope Technologies', deviceJoinName: 'Sinope Load Controller'
    }

    preferences {
        input name: 'powerReport', type: 'number', title: 'Power change', description: 'Amount of wattage difference to trigger power report (1..*)',  range: '1..*', defaultValue: 30
        input name: 'energyChange', type: 'number', title: 'Energy increment', description: 'Minimum increment of the energy meter in Wh to trigger energy reporting (10..*)', range: '10..*', defaultValue: 10
        input name: 'energyPrice', type: 'float', title: 'c/kWh Cost:', description: 'Electric Cost per kWh in cent', range: '0..*', defaultValue: 9.38
        input name: 'weeklyReset', type: 'enum', title: 'Weekly reset day', description: 'Day on which the weekly energy meter return to 0', options:['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], defaultValue: 'Sunday', multiple: false, required: true
        input name: 'yearlyReset', type: 'enum', title: 'Yearly reset month', description: 'Month on which the yearly energy meter return to 0', options:['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'], defaultValue: 'January', multiple: false, required: true
        input name: 'txtEnable', type: 'bool', title: 'Enable description text logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
    }
}

//-- Capabilities -----------------------------------------------------------------------------------------

def configure() {
    logInfo('configure()')
    try {
        unschedule()
    }
    catch (e)
    {
    }

    energyScheduling()

    state.switchTypeDigital = false

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null)                               // On/off state
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x29, 30, 600)                                    // Voltage
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x29, 30, 600, (int) powerReport * 4)               // Current
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) powerReport)                 // Active power reporting
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) energyChange)    // Energy reading

    if (cmds) {
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
    return
}

def refresh() {
    logInfo('refresh()')

    def cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000)    // Read on/off state
    cmds += zigbee.readAttribute(0x0B04, 0x0505)    // Read voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508)    // Read current
    cmds += zigbee.readAttribute(0x0B04, 0x050B)    // Read active power
    cmds += zigbee.readAttribute(0x0702, 0x0000)    // Read energy delivered

    if (cmds) {
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
}

def off() {
    state.switchTypeDigital = true
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)
}

def on() {
    state.switchTypeDigital = true
    def cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)
}

//-- Speudo library -----------------------------------------------------------------------------------------
/**
 *  Speudo library that is used with Sinope drivers
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/speudoLibrary.groovy
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
 * v1.0.0 Initial commit (2024-11-28)
 */

// Constants
import groovy.transform.Field

@Field static final Map constBacklightModes = [ 'off': 0x0, 'adaptive': 0x1, 'on': 0x1,
                                               0x0: 'off', 0x1: 'on' ]
@Field static final Map constBacklightModesG2 = [ 'off': 0x2, 'adaptive': 0x0, 'on': 0x1,
                                                 0x2: 'off', 0x0: 'adaptive', 0x1: 'on' ]
@Field static final Map constSecondTempDisplayModes =  [ 0x0 : 'auto', 0x01: 'setpoint', 0x02: 'outdoor',
                                                        'auto': 0x0, 'setpoint': 0x1, 'outdoor': 0x2 ]
@Field static final Map constThermostatCycles = [ 'short': 0x000F, 'long': 0x0384,
                                                 0x000F: 'short', 0x0384: 'long']

//-- Installation ----------------------------------------------------------------------------------------
def installed() {
    logInfo('installed() : running configure()')
    configure()
    refresh()
}

def initialize() {
    logInfo('installed() : running configure()')
    configure()
    refresh()
}

def updated() {
    logInfo('updated() : running configure()')
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
        state.updatedLastRanAt = now()
        configure()
        refresh()
    }
}

def uninstalled() {
    logInfo('uninstalled() : unscheduling')
    try {
        unschedule()
    }
    catch (errMsg)
    {
        log.error "${device.displayName} : uninstalled() - unschedule() threw an exception : ${errMsg}"
    }
}

//-- Parsing -----------------------------------------------------------------------------------------
def parse(String description) {
    logDebug("parse - description = ${description}")
    def descMap = zigbee.parseDescriptionAsMap(description)
    def value
    def name
    def unit
    def descriptionText
    def type
    switch (descMap.clusterInt)
    {
        case 0x0006:
            switch (descMap.attrInt)
            {
                case 0x0000:
                    name = 'switch'
                    value = getSwitchMap()[descMap.value]
                    type = state.switchTypeDigital ? 'digital' : 'physical'
                    state.switchTypeDigital = false
                    descriptionText = "${device.displayName} is set at ${value} [${type}]"
                    break
            }
            break

        case 0x0024:
            switch (descMap.attrInt)
            {
                case 0x0001:
                    name = 'lock'
                    value = getLockMap()[descMap.value]
                    descriptionText = "${device.displayName} is ${value}"
                    break
            }
            break

        case 0x0201:
            switch (descMap.attrInt)
            {
                case 0x0000:
                    name = 'temperature'
                    value = getTemperature(descMap.value)
                    unit = getTemperatureScale()
                    if (value > 158) {
                        value = 'Sensor Error'
                    }
                    descriptionText = "Temperature of ${device.displayName} is at ${value}${unit}"
                    break

                case 0x0008:
                    name = 'thermostatOperatingState'
                    value = getHeatingDemand(descMap.value)
                    descriptionText = "${device.displayName} is ${value}"
                    if (device.currentValue('maxPower') != null) {
                        def maxPowerValue = device.currentValue('maxPower').toInteger()
                        def powerValue = Math.round(maxPowerValue * value / 100)
                        sendEvent(name: 'power', value: powerValue, unit: 'W', descriptionText: "${device.displayName} is heating at ${powerValue}W")
                    }
                    sendEvent(name: 'heatingDemand', value: value, unit: '%', descriptionText: "${device.displayName} is heating at ${value}% of its capacity")
                    value = (value < 5) ? 'idle' : 'heating'
                    break

                case 0x0012:
                    name = 'heatingSetpoint'
                    value = getTemperature(descMap.value)
                    unit = getTemperatureScale()
                    type = state.setTemperatureTypeDigital ? 'digital' : 'physical'
                    state.setTemperatureTypeDigital = false
                    descriptionText = "The heating set point of ${device.displayName} is set at ${value}${unit} [${type}]"
                    sendEvent(name: 'thermostatSetpoint', value: value, unit: getTemperatureScale()) //For interoperability with SharpTools
                    break

                case 0x001C:
                    name = 'thermostatMode'
                    value = getModeMap()[descMap.value]
                    descriptionText = "The mode of ${device.displayName} is set at ${value}"
                    break
            }
            break

        case 0x0402:
            switch (descMap.attrInt)
            {
                case 0x0000:
                    name = 'temperature'
                    value = getTemperature(descMap.value)
                    unit = getTemperatureScale()
                    if (value > 158) {
                        value = 'Sensor Error'
                    }
                    descriptionText = "Water temperature of ${device.displayName} is at ${value}${unit}"
                    break
            }
            break

        case 0x0500:
            switch (descMap.attrInt)
            {
                case 0x0002:
                    name = 'water'
                    value = getWaterStatusMap()[descMap.value]
                    descriptionText = "Water leak sensor reports ${value}"
                    break
            }
            break

        case 0x0702:
            switch (descMap.attrInt)
            {
                case 0x0000:
                    state.energyValue = getEnergy(descMap.value) as BigInteger
                    runIn(2, 'energyCalculation')
                    break
            }
            break

        case 0x0B04: // None of those are autoreported.
            switch (descMap.attrInt)
            {
                case 0x0505:
                    name = 'voltage'
                    value = getRMSVoltage(descMap.value)
                    unit = 'V'
                    descriptionText = "Voltage is ${value} ${unit}"
                    break

                case 0x0508:
                    name = 'amperage'
                    value = getRMSCurrent(descMap.value)
                    unit = 'A'
                    descriptionText = "Current is ${value} ${unit}"
                    break

                case 0x050B:
                    name = 'power'
                    value = getActivePower(descMap.value)
                    unit = 'W'
                    descriptionText = "${device.displayName} is delivering ${value}${unit}"
                    break

                case 0x050D:
                    name = 'maxPower'
                    value = getActivePower(descMap.value)
                    unit = 'W'
                    descriptionText = "The max heating power of ${device.displayName} is ${value}${unit}"
                    break

                default:
                    logDebug("unhandled electrical measurement attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case 0xFF01:
            switch (descMap.attrInt)
            {
                case 0x0076:
                    name = 'safetyWaterTemp'
                    value = getSafetyWaterTemperature(descMap.value)
                    descriptionText = "Safety water temperature reports ${value}"
                    break

                case 0x010C:
                    name = 'floorLimitStatus'
                    value = descMap.value.toInteger()
                    if (value == 0) {
                        value = 'OK'
                    }
                    else if (value == 1)
                        value = 'floorLimitLowReached'
                    else if (value == 2)
                        value = 'floorLimitMaxReached'
                    else if (value == 3)
                        value = 'floorAirLimitMaxReached'
                    else
                        value = 'floorAirLimitMaxReached'

                    descriptionText = "The floor limit status of ${device.displayName} is ${value}}"
                    break

                case 0x0115:
                    name = 'gfciStatus'
                    value = descMap.value.toInteger()
                    if (value == 0) {
                        value = 'OK'
                    }
                    else if (value == 1)
                        value = 'error'
                    descriptionText = "The gfci status of ${device.displayName} is ${value}}"
                    break
                default:
                    //logDebug("unhandled custom attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        default:
            logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
            break
    }

    if (value) {
        sendEvent(name:name, value:value, descriptionText:descriptionText, unit:unit, type:type)
    }
}

//-- Energy function---------------------------------------------------------------------------------------

def energyScheduling() {
    schedule('0 0 * * * ? *', energySecCalculation)
    schedule('0 0 0 * * ? *', resetDailyEnergy)
    schedule('0 0 0 1 * ? *', resetMonthlyEnergy)

    if (weeklyReset == null) {
        weeklyReset = 'Sunday' as String
    }
    if (yearlyReset == null) {
        yearlyReset = 'January' as String
    }

    switch (yearlyReset) {
        case 'January' :
            schedule('0 0 0 1 1 ? *', resetYearlyEnergy)
            break
        case 'February' :
            schedule('0 0 0 1 2 ? *', resetYearlyEnergy)
            break
        case 'March' :
            schedule('0 0 0 1 3 ? *', resetYearlyEnergy)
            break
        case 'April' :
            schedule('0 0 0 1 4 ? *', resetYearlyEnergy)
            break
        case 'May' :
            schedule('0 0 0 1 5 ? *', resetYearlyEnergy)
            break
        case 'June' :
            schedule('0 0 0 1 6 ? *', resetYearlyEnergy)
            break
        case 'July' :
            schedule('0 0 0 1 7 ? *', resetYearlyEnergy)
            break
        case 'August' :
            schedule('0 0 0 1 8 ? *', resetYearlyEnergy)
            break
        case 'September' :
            schedule('0 0 0 1 9 ? *', resetYearlyEnergy)
            break
        case 'October' :
            schedule('0 0 0 1 10 ? *', resetYearlyEnergy)
            break
        case 'November' :
            schedule('0 0 0 1 11 ? *', resetYearlyEnergy)
            break
        case 'December' :
            schedule('0 0 0 1 12 ? *', resetYearlyEnergy)
            break
    }

    switch (weeklyReset) {
        case 'Sunday' :
            schedule('0 0 0 ? * 1 *', resetWeeklyEnergy)
            break
        case 'Monday' :
            schedule('0 0 0 ? * 2 *', resetWeeklyEnergy)
            break
        case 'Tuesday' :
            schedule('0 0 0 ? * 3 *', resetWeeklyEnergy)
            break
        case 'Wednesday' :
            schedule('0 0 0 ? * 4 *', resetWeeklyEnergy)
            break
        case 'Thursday' :
            schedule('0 0 0 ? * 5 *', resetWeeklyEnergy)
            break
        case 'Friday' :
            schedule('0 0 0 ? * 6 *', resetWeeklyEnergy)
            break
        case 'Saturday' :
            schedule('0 0 0 ? * 7 *', resetWeeklyEnergy)
            break
    }
}

def energyCalculation() {
    if (state.offsetEnergy == null) {
        state.offsetEnergy = 0 as BigInteger
    }
    if (state.dailyEnergy == null) {
        state.dailyEnergy = state.energyValue as BigInteger
    }
    if (device.currentValue('energy') == null) {
        sendEvent(name: 'energy', value: 0, unit: 'kWh', descriptionText: 'Total energy to 0 kWh')
    }
    if (energyPrice == null) {
        energyPrice = 9.38 as float
    }

    if (state.energyValue + state.offsetEnergy < device.currentValue('energy') * 1000) {
        //Although energy are parse as BigInteger, sometimes (like 1 times per month during heating  time) the value received is lower than the precedent but not zero..., so we define a new offset when that happen
        BigInteger newOffset = device.currentValue('energy') * 1000 - state.energyValue as BigInteger
        if (newOffset < 1e10) //Sometimes when the hub boot, the offset is very large... too large
            state.offsetEnergy = newOffset
    }

    float dailyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.dailyEnergy) / 1000)
    float totalEnergy = (state.energyValue + state.offsetEnergy) / 1000
    localCostPerKwh = energyPrice as float
    float dailyCost = roundTwoPlaces(dailyEnergy * localCostPerKwh / 100)

    sendEvent(name: 'dailyEnergy', value: dailyEnergy, unit: 'kWh', descriptionText: "Total daily energy is ${dailyEnergy} kWh")
    sendEvent(name: 'dailyCost', value: dailyCost, unit: "\$", descriptionText: "Total daily cost is ${dailyCost} \$")
    sendEvent(name: 'energy', value: totalEnergy, unit: 'kWh', descriptionText: "Total energy is ${totalEnergy} \$")
}

def energySecCalculation() {
    //This one is performed every hour to not overwhelm the number of events which will create a warning in hubitat main page
    if (state.weeklyEnergy == null) {
        state.weeklyEnergy = state.energyValue as BigInteger
    }
    if (state.monthlyEnergy == null) {
        state.monthlyEnergy = state.energyValue as BigInteger
    }
    if (state.yearlyEnergy == null) {
        state.yearlyEnergy = state.energyValue as BigInteger
    }
    if (energyPrice == null) {
        energyPrice = 9.38 as float
    }

    float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy) / 1000)
    float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy) / 1000)
    float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy) / 1000)
    float totalEnergy = (state.energyValue + state.offsetEnergy) / 1000

    localCostPerKwh = energyPrice as float
    float weeklyCost = roundTwoPlaces(weeklyEnergy * localCostPerKwh / 100)
    float monthlyCost = roundTwoPlaces(monthlyEnergy * localCostPerKwh / 100)
    float yearlyCost = roundTwoPlaces(yearlyEnergy * localCostPerKwh / 100)
    float totalCost = roundTwoPlaces(totalEnergy * localCostPerKwh / 100)

    sendEvent(name: 'weeklyEnergy', value: weeklyEnergy, unit: 'kWh', descriptionText: "Total weekly energy is ${weeklyEnergy} kWh")
    sendEvent(name: 'monthlyEnergy', value: monthlyEnergy, unit: 'kWh', descriptionText: "Total monthly energy is ${monthlyEnergy} kWh")
    sendEvent(name: 'yearlyEnergy', value: yearlyEnergy, unit: 'kWh', descriptionText: "Total yearly energy is ${yearlyEnergy} kWh")

    sendEvent(name: 'weeklyCost', value: weeklyCost, unit: "\$", descriptionText: "Total weekly cost is ${weeklyCost} \$")
    sendEvent(name: 'monthlyCost', value: monthlyCost, unit: "\$", descriptionText: "Total monthly cost is ${monthlyCost} \$")
    sendEvent(name: 'yearlyCost', value: yearlyCost, unit: "\$", descriptionText: "Total yearly cost is ${yearlyCost} \$")
    sendEvent(name: 'cost', value: totalCost, unit: "\$", descriptionText: "Total cost is ${totalCost} \$")
}

def resetEnergyOffset(text) {
    if (text != null) {
        BigInteger newOffset = text.toBigInteger()
        state.dailyEnergy = state.dailyEnergy - state.offsetEnergy + newOffset
        state.weeklyEnergy = state.weeklyEnergy - state.offsetEnergy + newOffset
        state.monthlyEnergy = state.monthlyEnergy - state.offsetEnergy + newOffset
        state.yearlyEnergy = state.yearlyEnergy - state.offsetEnergy + newOffset
        state.offsetEnergy = newOffset
        float totalEnergy = (state.energyValue + state.offsetEnergy) / 1000
        sendEvent(name: 'energy', value: totalEnergy, unit: 'kWh')
        runIn(2, energyCalculation)
        runIn(2, energySecCalculation)
    }
}

def resetDailyEnergy() {
    state.dailyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null) {
        energyPrice = 9.38 as float
    }
    localCostPerKwh = energyPrice as float
    float dailyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.dailyEnergy) / 1000)
    float dailyCost = roundTwoPlaces(dailyEnergy * localCostPerKwh / 100)
    sendEvent(name: 'dailyEnergy', value: dailyEnergy, unit: 'kWh')
    sendEvent(name: 'dailyCost', value: dailyCost, unit: "\$")
}

def resetWeeklyEnergy() {
    state.weeklyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null) {
        energyPrice = 9.38 as float
    }
    localCostPerKwh = energyPrice as float
    float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy) / 1000)
    float weeklyCost = roundTwoPlaces(weeklyEnergy * localCostPerKwh / 100)
    sendEvent(name: 'weeklyEnergy', value: weeklyEnergy, unit: 'kWh')
    sendEvent(name: 'weeklyCost', value: weeklyCost, unit: "\$")
}

def resetMonthlyEnergy() {
    state.monthlyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null) {
        energyPrice = 9.38 as float
    }
    localCostPerKwh = energyPrice as float
    float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy) / 1000)
    float monthlyCost = roundTwoPlaces(monthlyEnergy * localCostPerKwh / 100)
    sendEvent(name: 'monthlyEnergy', value: monthlyEnergy, unit: 'kWh')
    sendEvent(name: 'monthlyCost', value: monthlyCost, unit: "\$")
}

def resetYearlyEnergy() {
    state.yearlyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null) {
        energyPrice = 9.38 as float
    }
    localCostPerKwh = energyPrice as float
    float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy) / 1000)
    float yearlyCost = roundTwoPlaces(yearlyEnergy * localCostPerKwh / 100)
    sendEvent(name: 'yearlyEnergy', value: yearlyEnergy, unit: 'kWh')
    sendEvent(name: 'yearlyCost', value: yearlyCost, unit: "\$")
}

//-- Thermostat specific function-------------------------------------------------------------------------

def refreshTemp() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0201, 0x0000)  //Read Local Temperature

    if (cmds) {
        sendZigbeeCommands(cmds)
    }
}

def refreshMaxPower() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0B04, 0x050D)  //Read highest power delivered

    if (cmds) {
        sendZigbeeCommands(cmds)
    }
}

def refreshTime() {
    def cmds = []
    // Time
    def thermostatDate = new Date()
    def thermostatTimeSec = thermostatDate.getTime() / 1000
    def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60
    int currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800) //time from 2000-01-01 00:00
    cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, currentTimeToDisplay, [mfgCode: '0x119C'])

    if (cmds) {
        sendZigbeeCommands(cmds)
    }
}

def setClockTime() {
    refreshTime()
}

def displayOn() {
    setBacklightMode('on')
}

def displayOff() {
    setBacklightMode('off')
}

def displayAdaptive() {
    setBacklightMode('adaptive')
}

def auto() {
    log.warn 'auto(): mode is not available for this device. => Defaulting to heat mode instead.'
    heat()
}

def cool() {
    log.warn 'cool(): mode is not available for this device. => Defaulting to heat mode instead.'
    heat()
}

def emergencyHeat() {
    log.warn 'emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead.'
    heat()
}

def fanAuto() {
    log.warn 'fanAuto(): mode is not available for this device'
}

def fanCirculate() {
    log.warn 'fanCirculate(): mode is not available for this device'
}

def fanOn() {
    log.warn 'fanOn(): mode is not available for this device'
}

def setCoolingSetpoint(degrees) {
    log.warn "setCoolingSetpoint(${degrees}): is not available for this device"
}

def setHeatingSetpoint(preciseDegrees) {
    if (preciseDegrees != null) {
        unschedule('Setpoint')
        state.setPoint = preciseDegrees
        runInMillis(500, 'Setpoint')
    }
}

def Setpoint() {
    if (state.setPoint != device.currentValue('heatingSetpoint')) {
        runIn(30, 'Setpoint') //To make sure that set point temperature is always received by the device. 30 seconds to not overload in case of power outage when device is not responding
        def temperatureScale = getTemperatureScale()
        def degrees = state.setPoint
        def cmds = []

        logInfo("setHeatingSetpoint(${degrees}:${temperatureScale})")
        state.setTemperatureTypeDigital = true

        def celsius = (temperatureScale == 'C') ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)

        cmds += zigbee.writeAttribute(0x0201, 0x0012, 0x29, celsius100) //Write Heat Setpoint

        // Submit zigbee commands
        sendZigbeeCommands(cmds)
    }
}

def setThermostatFanMode(fanmode) {
    log.warn "setThermostatFanMode(${fanmode}): is not available for this device"
}

def setThermostatMode(String value) {
    logInfo("setThermostatMode(${value})")

    switch (value) {
        case 'heat':
        case 'emergency heat':
        case 'auto':
        case 'cool':
            return heat()

        case 'off':
            return off()
    }
}

def unlock() {
    logInfo('unlock()')
    sendEvent(name: 'lock', value: 'unlocked')

    def cmds = []
    cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)

    sendZigbeeCommands(cmds)
}

def lock() {
    logInfo('lock()')
    sendEvent(name: 'lock', value: 'locked')

    def cmds = []
    cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)

    sendZigbeeCommands(cmds)
}

def deviceNotification(text) {
    if (text != null) {
        double outdoorTemp = text.toDouble()
        def updateDescriptionText = "Received outdoor weather report : ${outdoorTemp} ${getTemperatureScale()}"
        sendEvent(name: 'outdoorTemp', value: outdoorTemp, unit: getTemperatureScale(), descriptionText: updateDescriptionText)
        logInfo(updateDescriptionText) // TODO : should be done in createCustomMap() for all events with descriptionText

        // the value sent to the thermostat must be in C
        if (getTemperatureScale() == 'F') {
            outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
        }

        def int outdoorTempDevice = outdoorTemp * 100  // device expects hundredths
        def cmds = []
        cmds += zigbee.writeAttribute(0xFF01, 0x0010, DataType.INT16, outdoorTempDevice, [mfgCode: '0x119C']) //set the outdoor temperature as integer
        sendZigbeeCommands(cmds)
    }
}

private setBacklightMode(mode = prefBacklightMode) {
    def backlightModeAttr = null
    if (isG2Model()) {
        backlightModeAttr = constBacklightModesG2[mode]
    }
    else
    {
        backlightModeAttr = constBacklightModes[mode]
    }

    if (backlightModeAttr == null) {
        log.warn("invalid display mode ${mode}")
        return
    }

    logDebug("setting display backlight to ${mode} (${backlightModeAttr})")
    device.updateSetting('prefBacklightMode', [value: mode, type: 'enum'])
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, backlightModeAttr, [mfgCode: '0x119C'])
    sendZigbeeCommands(cmds) // Submit zigbee commands
}

private setSecondTempDisplay(mode = prefSecondTempDisplay) {
    def BigInteger secondDisplaySetting = constSecondTempDisplayModes[mode]
    if (secondDisplaySetting != null) {
        logDebug("setting secondary temperature display to ${mode} (${secondDisplaySetting})")
        device.updateSetting('prefSecondTempDisplay', [value: mode, type: 'enum'])
        def cmds = []
        cmds += zigbee.writeAttribute(0xFF01, 0x0012, DataType.ENUM8, secondDisplaySetting, [mfgCode: '0x119C'])
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
    else
    {
        log.warn("invalid secondary temperature display mode ${mode}")
    }
}

private isG2Model() {
    device.getDataValue('model').contains('-G2')
}

private setThermostatCycle(cycle = prefCycleLength) {
    def int shortCycleAttr = constThermostatCycles[cycle]
    if (shortCycleAttr != null) {
        logDebug("setting thermostat cycle to ${cycle} (${shortCycleAttr})")
        device.updateSetting('prefCycleLength', [value: cycle, type: 'enum'])
        def cmds = []
        cmds += zigbee.writeAttribute(0x0201, 0x0401, DataType.UINT16, shortCycleAttr, [mfgCode: '0x119C'])
        sendZigbeeCommands(cmds)
    }
}

private getModeMap() {
    [
        '00': 'off',
        '04': 'heat'
    ]
}

private getLockMap() {
    [
        '00': 'unlocked ',
        '01': 'locked ',
    ]
}

//-- Water heater function--------------------------------------------------------------------------------
def enableSafetyWaterTemp() {
    def cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0076, DataType.UINT8, 45, [mfgCode: '0x119C'])
    sendZigbeeCommands(cmds)
}

def disableSafetyWaterTemp() {
    def cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0076, DataType.UINT8, 0, [mfgCode: '0x119C'])
    sendZigbeeCommands(cmds)
}

private getWaterStatusMap() {
    [
        '0030': 'dry',
        '0031': 'wet',
    ]
}

private getSafetyWaterTemperature(value) {
    switch (value) {
        case '2D' :
            logInfo('Safety water temperature is enabled')
            device.updateSetting('prefSafetyWaterTemp', true)
            return 'true'
        case '00' :
            log.warn 'Safety water temperature is disabled, water temperature can go below 45°C / 113°F without turning back on by itself'
            device.updateSetting('prefSafetyWaterTemp', false)
            return 'false'
    }
}

//-- Other generic functions -----------------------------------------------------------------------------------
private getTemperature(value) {
    if (value != null) {
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == 'C') {
            return celsius
        }
        else
        {
            return roundTwoPlaces(celsiusToFahrenheit(celsius))
        }
    }
}

private getSwitchMap() {
    [
    '00': 'off',
    '01': 'on',
  ]
}

private getActivePower(value) {
    if (value != null) {
        if (state.powerDivider == null) {
            state.powerDivider = 1 as Integer
        }
        return Integer.parseInt(value, 16) / state.powerDivider
    }
}

private getRMSVoltage(attributeReportValue) {
    if (attributeReportValue != null) {
        return Integer.parseInt(attributeReportValue, 16)
    }
}

private getRMSCurrent(attributeReportValue) {
    // attribute report is in mA
    if (attributeReportValue != null) {
        return Integer.parseInt(attributeReportValue, 16) / 1000
    }
}

private getEnergy(value) {
    if (value != null) {
        BigInteger EnergySum = new BigInteger(value, 16)
        return EnergySum
    }
}

private getTemperatureScale() {
    return "${location.temperatureScale}"
}

private getHeatingDemand(value) {
    if (value != null) {
        def demand = Integer.parseInt(value, 16)
        return demand
    }
}

private roundTwoPlaces(val) {
    return Math.round(val * 100) / 100
}

private hex(value) {
    String hex = new BigInteger(Math.round(value).toString()).toString(16)
    return hex
}

private logInfo(message) {
    if (infoEnable) log.info("${device.displayName} : ${message}")
}

private logDebug(message) {
    if (debugEnable) log.debug("${device.displayName} : ${message}")
}

private void sendZigbeeCommands(cmds) {
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

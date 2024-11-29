 /**
 *  Sinope TH1123ZB, TH1124ZB Device Driver for Hubitat
 *  Source: https://raw.githubusercontent.com/sacua/SinopeDriverHubitat/main/drivers/mergeDrivers/TH112xZB_Sinope_Hubitat.groovy
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements and from the driver of scoulombe79 and kris2k2
 *  Source: https://support.sinopetech.com/en/wp-content/uploads/sites/4/2019/08/Sinope-Technologies-Zigbee-Thermostat-V.1.0.0-SVN-547-1.txt
 *  Source: https://github.com/scoulombe79/Hubitat/blob/master/Drivers/Thermostat-Sinope-TH1123ZB.groovy
 *  Source: https://github.com/kris2k2/hubitat/blob/master/drivers/kris2k2-Sinope-TH112XZB.groovy
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
 * v1.1.0 Dont remember
 * v1.2.0 Correction for attribute reading for heat and off command
 * v1.3.0 Correction for the offset calculation very rarely, the reading is a super large negative value, when that happen, the offset does not change
 * v1.4.0 Enable debug parse, send event at configure for the supported mode and possible to reset the offset value
 * v1.5.0 Enable custom time for reset and manual reset
 * v1.5.1 Correction of bug for the reset of energy meter
 * v1.6.0 fix backlight control for G2 thermostat
 * v1.7.0 Adding cycle length control
 * v1.7.1 Correction of bug regarding logging debug and info
 * v2.0.0 Major code cleaning - Pseudo library being used - new capabilities added (2024-11-28)
 */

metadata {
    definition(name: 'Thermostat TH112xZB with energy meter', namespace: 'sacua', author: 'Samuel Cuerrier Auclair') {
        capability 'Thermostat'
        capability 'Configuration'
        capability 'TemperatureMeasurement'
        capability 'Refresh'
        capability 'Lock'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'CurrentMeter'
        capability 'VoltageMeasurement'
        capability 'Notification' // Receiving temperature notifications via RuleEngine

        attribute 'outdoorTemp', 'number'
        attribute 'heatingDemand', 'number'
        attribute 'cost', 'number'
        attribute 'dailyCost', 'number'
        attribute 'weeklyCost', 'number'
        attribute 'monthlyCost', 'number'
        attribute 'yearlyCost', 'number'
        attribute 'dailyEnergy', 'number'
        attribute 'weeklyEnergy', 'number'
        attribute 'monthlyEnergy', 'number'
        attribute 'yearlyEnergy', 'number'
        attribute 'maxPower', 'number'

        command 'refreshTime' //Refresh the clock on the thermostat
        command 'setClockTime' //Same as above, for compatibility with built-in driver (e.g. for Rule Machine)
        command 'displayOn'
        command 'displayOff'
        command 'displayAdaptive'
        command 'refreshTemp' //To refresh only the temperature reading
        command 'resetEnergyOffset', ['number']
        command 'resetDailyEnergy'
        command 'resetWeeklyEnergy'
        command 'resetMonthlyEnergy'
        command 'resetYearlyEnergy'

        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '000A,FF01,0019', model: 'TH1123ZB', manufacturer: 'Sinope Technologies', deviceJoinName: 'Sinope Thermostat TH1123ZB'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '0003,000A,0019', model: 'TH1123ZB-G2', manufacturer:'Sinope Technologies', deviceJoinName: 'Sinope Thermostat TH1123ZB-G2'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '000A,FF01,0019', model: 'TH1124ZB', manufacturer: 'Sinope Technologies', deviceJoinName: 'Sinope Thermostat TH1124ZB'
    }

    preferences {
        input name: 'prefBacklightMode', type: 'enum', title: 'Display backlight', options: ['off': 'On Demand', 'adaptive': 'Adaptive (default)', 'on': 'Always On'], defaultValue: 'adaptive', required: true
        input name: 'prefSecondTempDisplay', type: 'enum', title: 'Secondary Temp. Display', options:['auto': 'Auto (default)', 'setpoint': 'Setpoint', 'outdoor': 'Outdoor'], defaultValue: 'auto', required: true
        input name: 'prefTimeFormatParam', type: 'enum', title: 'Time Format', options:['24h', '12h AM/PM'], defaultValue: '24h', multiple: false, required: true
        input name: 'prefCycleLength', type: 'enum', title: 'Thermostat Cycle Length', options: ['short', 'long'], defaultValue: 'short', multiple: false, required: true
        input name: 'tempChange', type: 'number', title: 'Temperature change', description: 'Minumum change of temperature reading to trigger report in Celsius/100, 5..50', range: '5..50', defaultValue: 50
        input name: 'heatingChange', type: 'number', title: 'Heating change', description: 'Minimum change in the PI heating in % to trigger power and PI heating reporting, 1..25', range: '1..25', defaultValue: 5
        input name: 'energyChange', type: 'number', title: 'Energy increment', description: 'Minimum increment of the energy meter in Wh to trigger energy reporting, 10..*', range: '10..*', defaultValue: 10
        input name: 'energyPrice', type: 'float', title: 'c/kWh Cost:', description: 'Electric Cost per kWh in cent', range: '0..*', defaultValue: 9.38
        input name: 'weeklyReset', type: 'enum', title: 'Weekly reset day', description: 'Day on which the weekly energy meter return to 0', options:['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], defaultValue: 'Sunday', multiple: false, required: true
        input name: 'yearlyReset', type: 'enum', title: 'Yearly reset month', description: 'Month on which the yearly energy meter return to 0', options:['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'], defaultValue: 'January', multiple: false, required: true
        input name: 'infoEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug logging', description: '<br>', defaultValue: true
    }
}
//-- Capabilities -----------------------------------------------------------------------------------------

def configure() {
    logInfo('configure()')
    // Set unused default values
    sendEvent(name: 'coolingSetpoint', value:getTemperature('0BB8')) // 0x0BB8 =  30 Celsius
    sendEvent(name: 'thermostatFanMode', value:'auto') // We dont have a fan, so auto it is
    sendEvent(name: 'supportedThermostatModes', value:  '[\"off\", \"heat\"]') //We set the supported thermostat mode
    sendEvent(name: 'supportedThermostatFanModes', value:  '[\"auto\"]') //We set the supported thermostat mode

    try {
        unschedule()
    }
    catch (e)
    {
    }

    state.setTemperatureTypeDigital = false

    int timesec = Math.abs( new Random().nextInt() % 59)
    int timemin = Math.abs( new Random().nextInt() % 59)
    int timehour = Math.abs( new Random().nextInt() % 2)
    schedule(timesec + ' ' + timemin + ' ' + timehour + '/3 * * ? *', refreshTime) //refresh the clock at random begining and then every 3h
    timesec = Math.abs( new Random().nextInt() % 59)
    timemin = Math.abs( new Random().nextInt() % 59)
    timehour = Math.abs( new Random().nextInt() % 23)
    schedule(timesec + ' ' + timemin + ' ' + timehour + ' * * ? *', refreshMaxPower) //refresh maximum power capacity of the equipement wired to the thermostat one time per day at a random moment

    energyScheduling()

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    if (tempChange == null) {
        tempChange = 50 as int
    }
    if (heatingChange == null) {
        heatingChange = 5 as int
    }
    if (energyChange == null) {
        energyChange = 10 as int
    }

    cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 30, 580, (int) tempChange)                  // local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, 0x0020, 59, 590, (int) heatingChange)             // PI heating demand
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) energyChange)    // Energy reading
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x29, 30, 600)                                    // Voltage
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x29, 30, 600)                                    // Current
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 15, 302, 40)                              // occupied heating setpoint
    cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)                                       // temperature display mode
    cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)                                       // keypad lockout


    // Configure displayed scale
    if (getTemperatureScale() == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)    // Wr °C on thermostat display
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)    // Wr °F on thermostat display
    }

    // Configure display mode
    if (prefBacklightMode == null) {
        prefBacklightMode = 'adaptive' as String
    }
    runIn(1, 'setBacklightMode')

    // Configure secondary display
    if (prefSecondTempDisplay == null) {
        prefSecondTempDisplay = 'setpoint' as String
    }
    runIn(1, 'setSecondTempDisplay')

    // Configure Outdoor Weather parameters
    cmds += zigbee.writeAttribute(0xFF01, 0x0011, DataType.UINT16, 10800, [mfgCode: '0x119C'])  //set the outdoor temperature timeout to 3 hours

    //Configure Clock Format
    if (prefTimeFormatParam == null) {
        prefTimeFormatParam == '24h' as String
    }
    if (prefTimeFormatParam == '12h AM/PM') { //12h AM/PM "24h"
        logInfo('Set to 12h AM/PM')
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001)
    } else { //24h
        logInfo('Set to 24h')
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000)
    }

    // Configure thermostat cycle time (useful for fan-forced heaters, e.g. kickspace or bathroom heaters)
    if (prefCycleLength == null) {
        prefCycleLength = 'short' as String
    }
    runIn(1, 'setThermostatCycle')

    if (cmds) {
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
    return
}

def refresh() {
    logInfo('refresh()')
    runIn(2, 'refreshTime')
    def cmds = []
    cmds += zigbee.readAttribute(0x0B04, 0x050D)    // Read highest power delivered
    cmds += zigbee.readAttribute(0x0B04, 0x050B)    // Read thermostat Active power
    cmds += zigbee.readAttribute(0x0B04, 0x0505)    // Read voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508)    // Read amperage
    cmds += zigbee.readAttribute(0x0201, 0x0000)    // Read Local Temperature
    cmds += zigbee.readAttribute(0x0201, 0x0008)    // Read PI Heating State
    cmds += zigbee.readAttribute(0x0201, 0x0012)    // Read Heat Setpoint
    cmds += zigbee.readAttribute(0x0201, 0x001C)    // Read System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0000)    // Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001)    // Read Keypad Lockout
    cmds += zigbee.readAttribute(0x0702, 0x0000)    // Read energy delivered

    if (cmds) {
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
}

def heat() {
    logInfo('heat(): mode set')

    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 4)
    //cmds += zigbee.readAttribute(0x0201, 0x0008)
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

def off() {
    logInfo('off(): mode set, it means no heating!')

    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    //cmds += zigbee.readAttribute(0x0201, 0x0008)
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

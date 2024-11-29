/**
 *  Sinope TH1300ZB Device Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/mergeDrivers/TH1300ZB_Sinope_Hubitat.groovy
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements, from the driver of erilaj and from the TH112xZB driver
 *  Source: https://www.sinopetech.com/wp-content/uploads/2019/03/Sinope-Technologies-TH1300ZB-V.1.0.5-SVN-503.txt
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/TH112xZB_Sinope_Hubitat.groovy
 *  Source: https://github.com/erilaj/hubitat/blob/main/drivers/Sinope/erilaj-Sinope-TH1300ZB.groovy
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
 * v1.1.0 New feature relatated to floor control
 * v1.1.1 Correction in the preference description
 * v1.2.0 Correction for attribute reading for heat and off command
 * v1.3.0 Correction for the offset calculation very rarely, the reading is a super large negative value, when that happen, the offset does not change
 * v1.4.0 Enable debug parse, send event at configure for the supported mode and possible to reset the offset value
 * v1.5.0 Enable custom time for reset and manual reset
 * v1.5.1 Correction of bug for the reset of energy meter
 * v1.5.2 Remove duplication of attribute declaration and change order of supportedThermostatModes
 * v2.0.0 Major code cleaning - Pseudo library being used - new capabilities added (2024-11-28)
 */

metadata
{
    definition(name: 'Thermostat TH1300ZB with energy meter', namespace: 'sacua', author: 'Samuel Cuerrier Auclair') {
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
        attribute 'yesterdayCost', 'number'
        attribute 'lastWeekCost', 'number'
        attribute 'lastMonthCost', 'number'
        attribute 'lastYearCost', 'number'
        attribute 'dailyEnergy', 'number'
        attribute 'weeklyEnergy', 'number'
        attribute 'monthlyEnergy', 'number'
        attribute 'yearlyEnergy', 'number'
        attribute 'yesterdayEnergy', 'number'
        attribute 'lastWeekEnergy', 'number'
        attribute 'lastMonthEnergy', 'number'
        attribute 'lastYearEnergy', 'number'

        //Attributes specific to floor heating device
        attribute 'gfciStatus', 'enum', ['OK', 'error']
        attribute 'floorLimitStatus', 'enum', ['OK', 'floorLimitLowReached', 'floorLimitMaxReached', 'floorAirLimitLowReached', 'floorAirLimitMaxReached']

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

        fingerprint endpoint: '1', profileId: '0104', inClusters: '0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01', outClusters: '0003', manufacturer: 'Sinope Technologies', model: 'TH1300ZB', deviceJoinName: 'Sinope Thermostat TH1300ZB'
    }

    preferences {
        input name: 'prefBacklightMode', type: 'enum', title: 'Display backlight', options: ['off': 'On Demand', 'adaptive': 'Adaptive (default)', 'on': 'Always On'], defaultValue: 'adaptive', required: true
        input name: 'prefSecondTempDisplay', type: 'enum', title: 'Secondary Temp. Display', options:['auto': 'Auto (default)', 'setpoint': 'Setpoint', 'outdoor': 'Outdoor'], defaultValue: 'auto', required: true
        input name: 'prefTimeFormatParam', type: 'enum', title: 'Time Format', options:['24h', '12h AM/PM'], defaultValue: '24h', multiple: false, required: true
        input name: 'prefAirFloorModeParam', type: 'enum', title: 'Control mode (Floor or Ambient temperature)', options: ['Ambient', 'Floor'], defaultValue: 'Floor', multiple: false, required: false
        input name: 'prefFloorSensorTypeParam', type: 'enum', title: 'Probe type (Default: 10k)', options: ['10k', '12k'], defaultValue: '10k', multiple: false, required: false
        input name: 'FloorMaxAirTemperatureParam', type: 'number', title:'Ambient high limit (5C to 36C / 41F to 97F)', description: 'The maximum ambient temperature limit when in floor control mode.', range: '5..97', required: false
        input name: 'FloorLimitMinParam', type: 'number', title:'Floor low limit (5C to 36C / 41F to 97F)', description: 'The minimum temperature limit of the floor when in ambient control mode.', range:'5..97', required: false
        input name: 'FloorLimitMaxParam', type: 'number', title:'Floor high limit (5C to 36C / 41F to 97F)', description: 'The maximum temperature limit of the floor when in ambient control mode.', range:'5..97', required: false

        input name: 'tempChange', type: 'number', title: 'Temperature change', description: 'Minumum change of temperature reading to trigger report in Celsius/100, 5..50', range: '5..50', defaultValue: 50
        input name: 'heatingChange', type: 'number', title: 'Heating change', description: 'Minimum change in the PI heating in % to trigger power and PI heating reporting, 1..25', range: '1..25', defaultValue: 5
        input name: 'energyChange', type: 'number', title: 'Energy increment', description: 'Minimum increment of the energy meter in Wh to trigger energy reporting, 10..*', range: '10..*', defaultValue: 10
        input name: 'energyPrice', type: 'float', title: 'c/kWh Cost:', description: 'Electric Cost per kWh in cent', range: '0..*', defaultValue: 9.38
        input name: 'weeklyReset', type: 'enum', title: 'Weekly reset day', description: 'Day on which the weekly energy meter return to 0', options:['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], defaultValue: 'Sunday', multiple: false, required: true
        input name: 'yearlyReset', type: 'enum', title: 'Yearly reset month', description: 'Month on which the yearly energy meter return to 0', options:['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'], defaultValue: 'January', multiple: false, required: true
        input name: 'infoEnable', type: 'bool', title: 'Enable logging info', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
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

    //Set the scheduling some at random moment
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
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 15, 302, 40)                              // occupied heating setpoint
    cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)                                       // temperature display mode
    cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)                                       // keypad lockout
    cmds += zigbee.configureReporting(0xFF01, 0x0115, 0x30, 10, 3600, 1)                                // report gfci status each hours
    cmds += zigbee.configureReporting(0xFF01, 0x010C, 0x30, 10, 3600, 1)                                // floor limit status each hours
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x29, 30, 600)                                    // Voltage
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x29, 30, 600)                                    // Current

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

    //Set the control heating mode
    if (prefAirFloorModeParam == null) {
        prefAirFloorModeParam = 'Ambient' as String
    }
    if (prefAirFloorModeParam == 'Ambient') { //Air mode
        logInfo('Set to Ambient mode')
        cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0001)
    } else { //Floor mode
        logInfo('Set to Floor mode')
        cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0002)
    }

    //set the type of sensor
    if (prefFloorSensorTypeParam == null) {
        prefFloorSensorTypeParam = '10k' as String
    }
    if (prefFloorSensorTypeParam == '12k') { //sensor type = 12k
        logInfo('Sensor type is 12k')
        cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0001)
    } else { //sensor type = 10k
        logInfo('Sensor type is 10k')
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

        MaxAirTemperatureValue = Math.min(Math.max(5, MaxAirTemperatureValue), 36) //We make sure that it is within the limit
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

        FloorLimitMinValue = Math.min(Math.max(5, FloorLimitMinValue), 36) //We make sure that it is within the limit
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

        FloorLimitMaxValue = Math.min(Math.max(5, FloorLimitMaxValue), 36) //We make sure that it is within the limit
        FloorLimitMaxValue =  FloorLimitMaxValue * 100
        cmds += zigbee.writeAttribute(0xFF01, 0x010A, 0x29, FloorLimitMaxValue)
    }
    else {
        cmds += zigbee.writeAttribute(0xFF01, 0x010A, 0x29, 0x8000)
    }

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
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 04, [mfgCode: '0x1185']) // SETPOINT MODE
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

def off() {
    logInfo('off(): mode set')

    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    //cmds += zigbee.readAttribute(0x0201, 0x0008)
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

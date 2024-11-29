/**
 *  Sinope RM3500ZB Water heater controller Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/mergeDrivers/RM3500ZB_Sinope_Hubitat.groovy
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
 * v1.0.1 Remove some bugs
 * v1.0.2 Comestic change in the code and better description
 * v1.1.0 Change reporting, add attributes and improve codes
 * v1.1.1 Typo
 * v2.0.0 Major code cleaning - Pseudo library being used (2024-11-28)
 */

metadata
{
    definition(name: 'Water heater controller RM3500ZB with energy meter', namespace: 'sacua', author: 'Samuel Cuerrier Auclair') {
        capability 'Switch'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Outlet'
        capability 'CurrentMeter'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'TemperatureMeasurement'
        capability 'CurrentMeter'
        capability 'VoltageMeasurement'
        capability 'WaterSensor'

        attribute 'safetyWaterTemp', 'enum', ['true', 'false']
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

        command 'resetEnergyOffset', ['number']
        command 'resetDailyEnergy'
        command 'resetWeeklyEnergy'
        command 'resetMonthlyEnergy'
        command 'resetYearlyEnergy'
        command 'enableSafetyWaterTemp'
        command 'disableSafetyWaterTemp'

        fingerprint inClusters: '0000,0002,0003,0004,0005,0006,0402,0500,0702,0B04,0B05,FF01', outClusters: '000A,0019', manufacturer: 'Sinope Technologies', model: 'RM3500ZB', deviceJoinName: 'Sinope Calypso Smart Water Heater Controller'
    }

    preferences {
        input name: 'tempChange', type: 'number', title: 'Temperature change', description: 'Minumum change of temperature reading to trigger report in Celsius/100, 10..200', range: '10..200', defaultValue: 100
        input name: 'powerReport', type: 'number', title: 'Power change', description: 'Amount of wattage difference to trigger power report (1..*)',  range: '1..*', defaultValue: 30
        input name: 'energyChange', type: 'number', title: 'Energy increment', description: 'Minimum increment of the energy meter in Wh to trigger energy reporting (10..*)', range: '10..*', defaultValue: 10
        input name: 'energyPrice', type: 'float', title: 'c/kWh Cost:', description: 'Electric Cost per kWh in cent', range: '0..*', defaultValue: 9.38
        input name: 'weeklyReset', type: 'enum', title: 'Weekly reset day', description: 'Day on which the weekly energy meter return to 0', options:['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], defaultValue: 'Sunday', multiple: false, required: true
        input name: 'yearlyReset', type: 'enum', title: 'Yearly reset month', description: 'Month on which the yearly energy meter return to 0', options:['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'], defaultValue: 'January', multiple: false, required: true
        input name: 'switchReportingSeconds', type: 'enum', title: 'Switch status reporting', description: 'Maximum time to report heater switch status even if no change', options:[0: 'never', 60: '1 minute', 600:'10 minutes', 3600:'1 hour', 21600:'6 hours', 43200:'12 hours', 86400:'24 hours'], defaultValue: '0', multiple: false, required: true
        input name: 'waterReportingSeconds', type: 'enum', title: 'Water leak sensor reporting', description: 'Maximum time to report water leak sensor status even if no change', options:[0: 'never', 60: '1 minute', 600:'10 minutes', 3600:'1 hour', 21600:'6 hours', 43200:'12 hours', 86400:'24 hours'], defaultValue: '0', multiple: false, required: true
        input name: 'prefSafetyWaterTemp', type: 'bool', title: 'Enable safety minimum water temperature feature (45°C/113°F)', defaultValue: true
        input name: 'infoEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
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

    state.switchTypeDigital = true

    // Prepare our zigbee commands
    def cmds = []

    // Configure Default values if null
    if (tempChange == null) {
        tempChange = 100 as int
    }
    if (powerReport == null) {
        powerReport = 30 as int
    }
    if (energyChange == null) {
        energyChange = 10 as int
    }
    if (waterReportingSeconds == null) {
        waterReportingSeconds = '0'
    }
    if (switchReportingSeconds == null) {
        switchReportingSeconds = '0'
    }
    if (prefSafetyWaterTemp == null) {
        prefSafetyWaterTemp = true
    }

    cmds += zigbee.configureReporting(0x0402, 0x0000, 0x29, 30, 580, (int) tempChange)                                  // Water temperature
    cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 0, Integer.parseInt(waterReportingSeconds))    // Water lear sensor state
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, Integer.parseInt(switchReportingSeconds))                // Heater On/off state
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x29, 30, 600)                                                    // Voltage
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x29, 30, 600, (int) powerReport * 4)                               // Current
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) powerReport)                                 // Active power reporting
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 299, 1799, (int) energyChange)                   // Energy reading
    cmds += zigbee.configureReporting(0xFF01, 0x0076, DataType.UINT8, 0, 86400, null, [mfgCode: '0x119C'])              // Safety water temp reporting every 24 hours

    // Configure Safety Water Temp
    if (!prefSafetyWaterTemp) {
        //        log.warn "Water temperature safety is off, water temperature can go below 45°C / 113°F without turning back on by itself"
        cmds += zigbee.writeAttribute(0xFF01, 0x0076, DataType.UINT8, 0, [mfgCode: '0x119C'])  //set water temp min to 0 (disabled)
    } else {
        //        logInfo("Water temperature safety is on")
        cmds += zigbee.writeAttribute(0xFF01, 0x0076, DataType.UINT8, 45, [mfgCode: '0x119C'])  //set water temp min to 45 (only acceptable value)
    }


    if (cmds) {
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
    return
}

def refresh() {
    logInfo('refresh()')

    def cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000)                        // Read Local Temperature
    cmds += zigbee.readAttribute(0x0500, 0x0002)                        // Read Water leak
    cmds += zigbee.readAttribute(0xFF01, 0x0076, [mfgCode: '0x119C'])   //Read state of water temp safety setting (45 or 0)
    cmds += zigbee.readAttribute(0x0006, 0x0000)                        // Read On off state
    cmds += zigbee.readAttribute(0x0B04, 0x050B)                        // Read thermostat Active power
    cmds += zigbee.readAttribute(0x0B04, 0x0505)                        // Read voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508)                        // Read amperage
    cmds += zigbee.readAttribute(0x0702, 0x0000)                        // Read energy delivered

    if (cmds) {
        sendZigbeeCommands(cmds) // Submit zigbee commands
    }
}

def off() {
    logDebug('command switch OFF')
    state.switchTypeDigital = true
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)
}

def on() {
    logDebug('command switch ON')
    state.switchTypeDigital = true
    def cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)
}

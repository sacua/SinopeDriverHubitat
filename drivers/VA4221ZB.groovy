/**
 *  Sinope TH1123ZB, TH1124ZB Device Driver for Hubitat
 *  Source: https://raw.githubusercontent.com/sacua/SinopeDriverHubitat/main/drivers/TH112xZB_Sinope_Hubitat.groovy
 *
 *  Code derived from Sinope's SmartThing driver
 *  https://github.com/Sinopetech/Smartthings/blob/master/Sinope%20Technologies%20VA4200WZ%20V.1.0.0.txt
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

metadata {
	definition(name: "Sinope water valve", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
		capability "Configuration"
        capability "Refresh"
        capability "Valve"
        capability "Battery"        
        capability "Power Source"   
		capability "LiquidFlowRate"
		
		attribute "volume", "number"


		preferences {
			input name: "flowRateChange", type: "number", title: "flow rate", description: "Minimum change in the flow rate to trigger autoreporting in ???", defaultValue: 1
			input name: "volumeChange", type: "number", title: "Energy increment", description: "Minimum change in volume of water deliver to trigger auto reporting in ???", defaultValue: 1
			input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
		}

		fingerprint profileId: "0104", inClusters: "0000, 0003, 0004, 0005, 0006, 0008, 0B05", outCluster: "0003, 0019", manufacturer: "Sinope Technologies", model: "VA4221ZB", deviceJoinName: "Sinope ZigBee Valve"

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
    
    log.debug "parse - description = ${description}"
    
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
        if (descMap.cluster == "0006" && descMap.attrId == "0000") {
            map.name = "valve"
            map.value = getValveMap()[descMap.value]
            
        } else if (descMap.cluster == "0000" && descMap.attrId == "0007") {
            map.name = "powerSource"
            map.value = getPowerSourceResult(descMap.value)
        
        } else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
            map.name = "battery"
            map.value = getBatteryResult(descMap.value)
			
        } else if (descMap.cluster == "0404" && descMap.attrId == "0000") {
            map.name = "rate"
            map.value = getRate(descMap.value)
			
        } else if (descMap.cluster == "0702" && descMap.attrId == "0000") {
			map.name = "volume"
            map.value = getVolume(descMap.value)
        }
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        result = createEvent(map)
    }
    return result
}

            
//-- Capabilities -----------------------------------------------------------------------------------------

def configure() {    
    if (txtEnable) log.info "configure()"    
    try
    {
        unschedule()
    }
    catch (e)
    {
    }
 
        
    // Prepare our zigbee commands
    def cmds = []
	
    if (flowRateChange == null)
		flowRateChange = 1 as int
    if (volumeChange == null)
		volumeChange = 1 as int
		

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 60, 3600, 1)  //Reporting for battery power
    cmds += zigbee.configureReporting(0x0404, 0x0000, DataType.UINT16, 30, 600, (int) flowRateChange)  //Report for flow rate
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) volumeChange) //Report for volume of water
    
    if (cmds)
      sendZigbeeCommands(cmds) // Submit zigbee commands
    return
}

def refresh() {
    if (txtEnable) log.info "refresh()"
    
    def cmds = []    
    cmds += zigbee.readAttribute(0x0006, 0x0000) //Read open close state
	cmds += zigbee.readAttribute(0x0000, 0x0007) //Read power source state
	cmds += zigbee.readAttribute(0x0001, 0x0020) //Read battery level
    cmds += zigbee.readAttribute(0x0404, 0x0000) //Read waterflow
    cmds += zigbee.readAttribute(0x0702, 0x0000) //Read volume of water delivered
    
    if (cmds)
        sendZigbeeCommands(cmds) // Submit zigbee commands
}   


def close() {
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)    
}

def open() {
    def cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)    
}


//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    //cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getValveMap() {
  [
    "00": "close",
    "01": "open",
  ]
}

private getPowerSourceResult(value) {
    if(value == "81" || value == "82"){
    	powersource = "mains"
    }
    else if(value == "03"){
    	powersource  = "battery"
    }
    else if(value == "04"){
    	powersource  = "dc"
    }
    else{
    	powersource  = "unknown"
    }
    return powersource
}


private getRate(value) {
  log.debug " getRate value = ${value}"
  if (value != null)
  {
    rate = Integer.parseInt(value, 16)
    return rate/10
  }
}

private getBatteryResult(value) {
  if (value != null)
  {
    // based on some stuff I found on the net but don't know how accurate it is...
    def batteryMin = 5.75f
    def batteryMax = 6.37f
    
	  voltage = Integer.parseInt(value, 16)/10 // convert from units of 100mV to V
    
    if(voltage < batteryMin)
      return 0
    
    output = ((voltage - batteryMin) / (batteryMax - batteryMin)) * 100
    if (output < 100)
        return (int)output
    else
        return 100i
    
  }
}


private getVolume(value) {
  log.debug "getVolume value = ${value}"
  if (value != null)
  {
    volume = new BigInteger(value,16)
    return volume
  }
}

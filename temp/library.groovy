 
library (
 author: "Samuel C. Auclair",
 category: "Energy",
 description: "Method related to energy that are shared between drivers",
 name: "EnergyLibrary",
 namespace: "sacua",
 documentationLink: "https://community.hubitat.com/t/release-sinope-device-drivers-with-advanced-functionality/91960"
)

// UPDATE TEST2
//-- Installation ----------------------------------------------------------------------------------------
def installed() {
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
def parse(String description) {
    def result = []
    def descMap = zigbee.parseDescriptionAsMap(description)

    logDebug("parse - description = ${descMap}")

    if (description?.startsWith("read attr -")) {
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

//-- Energy private functions ---------------------------------------------------------------------------
def energySchedule() {
    if (weeklyReset == null)
		weeklyReset = "Sunday" as String
	if (yearlyReset == null)
		yearlyReset = "January" as String

	switch (yearlyReset) {
		case "January" :
			schedule("0 0 0 1 1 ? *", resetYearlyEnergy)
			break
		case "February" :
			schedule("0 0 0 1 2 ? *", resetYearlyEnergy)
			break
		case "March" :
			schedule("0 0 0 1 3 ? *", resetYearlyEnergy)
			break
		case "April" :
			schedule("0 0 0 1 4 ? *", resetYearlyEnergy)
			break
		case "May" :
			schedule("0 0 0 1 5 ? *", resetYearlyEnergy)
			break
		case "June" :
			schedule("0 0 0 1 6 ? *", resetYearlyEnergy)
			break
		case "July" :
			schedule("0 0 0 1 7 ? *", resetYearlyEnergy)
			break
		case "August" :
			schedule("0 0 0 1 8 ? *", resetYearlyEnergy)
			break
		case "September" :
			schedule("0 0 0 1 9 ? *", resetYearlyEnergy)
			break
		case "October" :
			schedule("0 0 0 1 10 ? *", resetYearlyEnergy)
			break
		case "November" :
			schedule("0 0 0 1 11 ? *", resetYearlyEnergy)
			break
		case "December" :
			schedule("0 0 0 1 12 ? *", resetYearlyEnergy)
			break
	}

	switch (weeklyReset) {
		case "Sunday" :
			schedule("0 0 0 ? * 1 *", resetWeeklyEnergy)
			break
		case "Monday" :
			schedule("0 0 0 ? * 2 *", resetWeeklyEnergy)
			break
		case "Tuesday" :
			schedule("0 0 0 ? * 3 *", resetWeeklyEnergy)
			break
		case "Wednesday" :
			schedule("0 0 0 ? * 4 *", resetWeeklyEnergy)
			break
		case "Thursday" :
			schedule("0 0 0 ? * 5 *", resetWeeklyEnergy)
			break
		case "Friday" :
			schedule("0 0 0 ? * 6 *", resetWeeklyEnergy)
			break
		case "Saturday" :
			schedule("0 0 0 ? * 7 *", resetWeeklyEnergy)
			break
	}
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
	if (energyPrice == null)
		energyPrice = 9.38 as float

	if (state.energyValue + state.offsetEnergy < device.currentValue("energy")*1000) { //Although energy are parse as BigInteger, sometimes (like 1 times per month during heating  time) the value received is lower than the precedent but not zero..., so we define a new offset when that happen
		BigInteger newOffset = device.currentValue("energy")*1000 - state.energyValue as BigInteger
		if (newOffset < 1e10) //Sometimes when the hub boot, the offset is very large... too large
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
	if (energyPrice == null)
		energyPrice = 9.38 as float

	float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy)/1000)
	float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy)/1000)
	float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy)/1000)
	float totalEnergy = (state.energyValue + state.offsetEnergy)/1000

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
	cmds.removeAll { it.startsWith("delay") }
	def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
	sendHubCommand(hubAction)
}

private getTemperature(value) {
	if (value != null) {
		def celsius = Integer.parseInt(value, 16) / 100 + tempOffset
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

private getActivePower(value) {
	if (value != null) {
	def activePower = Integer.parseInt(value, 16)
	return activePower
	}
}

private getEnergy(value) {
	if (value != null) {
		BigInteger EnergySum = new BigInteger(value,16)
		return EnergySum
	}
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

private roundTwoPlaces(val) {
	return Math.round(val * 100) / 100
}

private logInfo(message) {
    if (infoEnable) log.info("${device} : ${message}")
}

private logDebug(message) {
    if (debugEnable) log.debug("${device} : ${message}")
}

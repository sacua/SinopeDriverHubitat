## TH112xZB_Sinope_Hubitat.groovy 
Driver for the zigbee sinope thermostats for electrical baseboard. At the installation, <b>you must save the preferences and then click configure</b>.

This driver allows to send via rule machine the outside temperature to the thermostats using the notification capability of the thermostat. Moreover, the thermostat have an energy meter capabilities. This driver retrieve the energy value from the thermostat and estimate the cost of energy. Also, there is a slight delay (0.5 seconds) programmed for the heating set point. This delay make the thermostat tile from the Hubitat dashboard response more consistent.

## SP2600ZB_Sinope_Hubitat.groovy
This driver is for the sinope outlet/switch. It will most likely works with several others Zigbee outlet/switch. Almost identical to the default driver, it however evaluate the energy of the outlet/switch by retriving this information from the switch. This driver have been tested with the outlet device of Sinope, I belive it will also works with the switch (SW2500ZB).

## RM3250ZB_Sinope_Hubitat.groovy
This driver is for the sinope load control switch. Almost identical to the previous switch driver, except that the power divisor is not the same.

## RM3500ZB_Sinope_Hubitat.groovy 
This driver is for the sinope water heater controller. It includes an energy meter, water temperature attribute and the water leak sensor. This driver was possible thanks to the work of [Nelson Clark](https://github.com/NelsonClark).

## TH1300ZB_Sinope_Hubitat.groovy
This driver is similar to TH112xZB, but have specific function related to floor heating.

## TH1400ZB_Sinope_Hubitat.groovy
This driver is for the low voltage thermostat of Sinope. It does not gives energy reporting since it cannot measure the actual power/energy delivered to the heating device.

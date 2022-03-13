## TH112xZB_Sinope_Hubitat.groovy 
Driver for the zigbee sinope thermostats. This driver allows to send via rule machine the outsite temperature to the thermostats using the notification capability of the thermostat. Moreover, the thermostat have an energy meter capabilities. This driver retrieve those values from the thermostat and estimate the cost of energy. Also, there is a slight delay (2 seconds) program for the heating set point. This delay make the thermostat tile from the Hubitat dashboard reponse more consistent.

## SP2600ZB_Sinope_Hubitat.groovy
This driver is for the sinope switch. Althought it will works with several others Zigbee switch. Almost identical to the default driver, it however evaluate the energy of the switch by retriving this information from the switch.

## RM3250ZB_Sinope_Hubitat.groovy
This driver is for the sinope load control switch. Almost identical to the previous switch driver, except that the power divisor is not the same.

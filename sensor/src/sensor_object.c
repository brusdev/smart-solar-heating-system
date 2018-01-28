/*
 MIT Licence
 Bruscino Domenico Francesco <bruscinodf@gmail.com>
*/

/*
 * Implements a generic sensor
 *
 *                 Multiple
 * Object |  ID  | Instances | Mandatoty |
 *  Sensor| 3300 |    Yes    |    No     |
 *
 *  Ressources:
 *              Supported    Multiple
 *  Name | ID | Operations | Instances | Mandatory |  Type   | Range | Units |      Description      |
 *  value|5700|    R       |    No     |    Yes    |  Float  |       |       |  Sensor Value         |
 *  unit |5701|    R       |    No     |    Yes    | String  |       |       |  Sensor Units         |
 *  minv |5601|    R       |    No     |    Yes    |  Float  |       |       |  Min Measured Value   |
 *  maxv |5602|    R       |    No     |    Yes    |  Float  |       |       |  Max Measured Value   |
 *  minr |5603|    R       |    No     |    Yes    |  Float  |       |       |  Min Range Value      |
 *  maxr |5604|    R       |    No     |    Yes    |  Float  |       |       |  Max Range Value      |
 *  appt |5750|    R/W     |    No     |    Yes    | String  |       |       |  Application Type     |
 *  type |5751|    R       |    No     |    Yes    | String  |       |       |  Sensor Type          |
 *  reset|5605|    E       |    No     |    Yes    | Opaque  |       |       |  Reset Min and Max    |
 *
 */

#include "sensor_object.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <limits.h>

#ifdef WIN32
#include <windows.h>
#endif

typedef struct _sensor_object_instance_
{
    /*
     * The first two are mandatories and represent the pointer to the next instance and the ID of this one. The rest
     * is the instance scope user data
     */
    struct sensor_object_instance_t * next;   // matches lwm2m_list_t::next
    uint16_t shortID;                       // matches lwm2m_list_t::id
    double   value;
    char*    unit;
    double   minv;
    double   maxv;
    double   minr;
    double   maxr;
    char*    appt;
    char*    type;
    void*    resetF;
} sensor_object_instance_t;

// We want to react to a write of the "state" ressource, therefore use the write_verify callback.
bool sensor_object_write_verify_cb(lwm2m_list_t* instance, uint16_t changed_res_id);

// Always do this in an implementation file not a header file
OBJECT_META(sensor_object_instance_t, sensor_object_meta, sensor_object_write_verify_cb,
    {5700, O_RES_R |O_RES_DOUBLE,        offsetof(sensor_object_instance_t,value)},
    {5701, O_RES_R |O_RES_STRING_STATIC, offsetof(sensor_object_instance_t,unit)},
    {5601, O_RES_R |O_RES_DOUBLE,        offsetof(sensor_object_instance_t,minv)},
    {5602, O_RES_R |O_RES_DOUBLE,        offsetof(sensor_object_instance_t,maxv)},
    {5603, O_RES_R |O_RES_DOUBLE,        offsetof(sensor_object_instance_t,minr)},
    {5604, O_RES_R |O_RES_DOUBLE,        offsetof(sensor_object_instance_t,maxr)},
    {5750, O_RES_RW|O_RES_STRING_STATIC, offsetof(sensor_object_instance_t,appt)},
    {5751, O_RES_R |O_RES_STRING_STATIC, offsetof(sensor_object_instance_t,type)},
    {5605, O_RES_E |O_RES_FUNCTION,      offsetof(sensor_object_instance_t,resetF)}
)

lwm2m_object_meta_information_t *sensor_object_get_meta() {
    return (lwm2m_object_meta_information_t*)sensor_object_meta;
}

void sensor_reset(uint8_t * buffer, int length) {
    printf("sensor_reset");
}

lwm2m_list_t* sensor_object_create_instances() {
    sensor_object_instance_t * targetP = (sensor_object_instance_t *)malloc(sizeof(sensor_object_instance_t));
    if (NULL == targetP) return NULL;
    memset(targetP, 0, sizeof(sensor_object_instance_t));
    targetP->shortID = 0;
    targetP->value = 0;
    targetP->unit = "Cel";
    targetP->minv = 0;
    targetP->maxv = 0;
    targetP->minr = -55;
    targetP->maxr = 125;
    targetP->appt = "Solar tank temperature";
    targetP->type = "Digital Thermometer";
    targetP->resetF = sensor_reset;
    return (lwm2m_list_t*)targetP;
}

bool sensor_object_write_verify_cb(lwm2m_list_t* instance, uint16_t changed_res_id) {
    sensor_object_instance_t* i = (sensor_object_instance_t*)instance;
    if(changed_res_id==5750) {
        printf("New Application Type: %s", i->appt);
    }

    return true;
}


// Copyright 2015 Comcast Cable Communications Management, LLC

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file was initially generated by gen_to_start.go (add link), as a start
// of the Traffic Ops golang data model

package api

import (
	"encoding/json"
	_ "github.com/Comcast/traffic_control/traffic_ops/experimental/server/output_format" // needed for swagger
	"github.com/jmoiron/sqlx"
	"log"
	"time"
)

type Regex struct {
	Id          int64     `db:"id" json:"id"`
	Pattern     string    `db:"pattern" json:"pattern"`
	Type        int64     `db:"type" json:"type"`
	LastUpdated time.Time `db:"last_updated" json:"lastUpdated"`
}

// @Title getRegexById
// @Description retrieves the regex information for a certain id
// @Accept  application/json
// @Param   id              path    int     false        "The row id"
// @Success 200 {array}    Regex
// @Resource /api/2.0
// @Router /api/2.0/regex/{id} [get]
func getRegexById(id int, db *sqlx.DB) (interface{}, error) {
	ret := []Regex{}
	arg := Regex{Id: int64(id)}
	nstmt, err := db.PrepareNamed(`select * from regex where id=:id`)
	err = nstmt.Select(&ret, arg)
	if err != nil {
		log.Println(err)
		return nil, err
	}
	nstmt.Close()
	return ret, nil
}

// @Title getRegexs
// @Description retrieves the regex information for a certain id
// @Accept  application/json
// @Success 200 {array}    Regex
// @Resource /api/2.0
// @Router /api/2.0/regex [get]
func getRegexs(db *sqlx.DB) (interface{}, error) {
	ret := []Regex{}
	queryStr := "select * from regex"
	err := db.Select(&ret, queryStr)
	if err != nil {
		log.Println(err)
		return nil, err
	}
	return ret, nil
}

// @Title postRegex
// @Description enter a new regex
// @Accept  application/json
// @Param                 Body body     Regex   true "Regex object that should be added to the table"
// @Success 200 {object}    output_format.ApiWrapper
// @Resource /api/2.0
// @Router /api/2.0/regex [post]
func postRegex(payload []byte, db *sqlx.DB) (interface{}, error) {
	var v Regex
	err := json.Unmarshal(payload, &v)
	if err != nil {
		log.Println(err)
	}
	sqlString := "INSERT INTO regex("
	sqlString += "pattern"
	sqlString += ",type"
	sqlString += ") VALUES ("
	sqlString += ":pattern"
	sqlString += ",:type"
	sqlString += ")"
	result, err := db.NamedExec(sqlString, v)
	if err != nil {
		log.Println(err)
		return nil, err
	}
	return result, err
}

// @Title putRegex
// @Description modify an existing regexentry
// @Accept  application/json
// @Param   id              path    int     true        "The row id"
// @Param                 Body body     Regex   true "Regex object that should be added to the table"
// @Success 200 {object}    output_format.ApiWrapper
// @Resource /api/2.0
// @Router /api/2.0/regex/{id}  [put]
func putRegex(id int, payload []byte, db *sqlx.DB) (interface{}, error) {
	var v Regex
	err := json.Unmarshal(payload, &v)
	v.Id = int64(id) // overwrite the id in the payload
	if err != nil {
		log.Println(err)
		return nil, err
	}
	v.LastUpdated = time.Now()
	sqlString := "UPDATE regex SET "
	sqlString += "pattern = :pattern"
	sqlString += ",type = :type"
	sqlString += ",last_updated = :last_updated"
	sqlString += " WHERE id=:id"
	result, err := db.NamedExec(sqlString, v)
	if err != nil {
		log.Println(err)
		return nil, err
	}
	return result, err
}

// @Title delRegexById
// @Description deletes regex information for a certain id
// @Accept  application/json
// @Param   id              path    int     false        "The row id"
// @Success 200 {array}    Regex
// @Resource /api/2.0
// @Router /api/2.0/regex/{id} [delete]
func delRegex(id int, db *sqlx.DB) (interface{}, error) {
	arg := Regex{Id: int64(id)}
	result, err := db.NamedExec("DELETE FROM regex WHERE id=:id", arg)
	if err != nil {
		log.Println(err)
		return nil, err
	}
	return result, err
}

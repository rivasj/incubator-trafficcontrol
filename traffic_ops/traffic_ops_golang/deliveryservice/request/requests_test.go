package request

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import (
	"encoding/json"
	"testing"

	"github.com/jmoiron/sqlx"
	sqlmock "gopkg.in/DATA-DOG/go-sqlmock.v1"
)

func TestGetDeliveryServiceRequest(t *testing.T) {
	r := &TODeliveryServiceRequest{
		ChangeType: "UPDATE",
		Status:     "submitted",
		Request: json.RawMessage(`{
			"xmlId" : "this is not a valid xmlid.  Bad characters and too long.",
			"status" : "submitted",
			"cdnId" : 1,
			"logsEnabled": false,
			"dscp" : null,
			"geoLimit" : 2,
			"active" : true,
			"displayName" : "",
			"typeId" : 3
		}`),
	}
	expectedErrors := []string{
		`'regionalGeoBlocking' is required`,
		`'xmlId' cannot contain spaces`,
		`'dscp' is required`,
		`'displayName' cannot be blank`,
		`'geoProvider' is required`,
		`'typeId' is required`,
	}

	r.SetID(10)
	if r.GetID() != 10 {
		t.Errorf("expected ID to be %d,  not %d", 10, r.GetID())
	}
	exp := "10"
	if r.GetAuditName() != exp {
		t.Errorf("expected AuditName to be %s,  not %s", exp, r.GetAuditName())
	}
	exp = "deliveryservice_request"
	if r.GetType() != "deliveryservice_request" {
		t.Errorf("expected Type to be %s,  not %s", exp, r.GetType())
	}

	mockDB, _, err := sqlmock.New()
	if err != nil {
		t.Fatalf("an error '%s' was not expected when opening a stub database connection", err)
	}
	defer mockDB.Close()

	db := sqlx.NewDb(mockDB, "sqlmock")
	defer db.Close()

	errs := r.Validate(db)
	if len(errs) != len(expectedErrors) {
		for _, e := range errs {
			t.Error(e)
		}
	}

	for e := range expectedErrors {
		t.Error(e)
	}

	/*
		if r.Update(db *sqlx.DB, ctx context.Context) {
			t.Errorf("expected ID to be %d,  not %d", 10, r.GetID())
		}
		if r.Insert(db *sqlx.DB, ctx context.Context) {
			t.Errorf("expected ID to be %d,  not %d", 10, r.GetID())
		}
		if r.Delete(db *sqlx.DB, ctx context.Context) {
			t.Errorf("expected ID to be %d,  not %d", 10, r.GetID())
		}
	*/
}
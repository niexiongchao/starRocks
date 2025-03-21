// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/common/utils.h

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <string>

namespace starrocks {

struct AuthInfo {
    std::string user;
    std::string passwd;
    std::string cluster;
    std::string user_ip;
    // -1 as unset
    int64_t auth_code = -1;
};

template <class T>
void set_request_auth(T* req, const AuthInfo& auth) {
    if (auth.auth_code != -1) {
        // If 'auth_code' is set, no need to set other info.
        req->__set_auth_code(auth.auth_code);
        // User name and passwd is unused, but they are required field.
        // so they have to be set.
        req->user = "";
        req->passwd = "";
    } else {
        req->user = auth.user;
        req->passwd = auth.passwd;
        if (!auth.cluster.empty()) {
            req->__set_cluster(auth.cluster);
        }
        req->__set_user_ip(auth.user_ip);
    }
}

} // namespace starrocks

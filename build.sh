#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

##############################################################
# This script is used to compile StarRocks
# Usage: 
#    sh build.sh --help
# Eg:
#    sh build.sh                            build all
#    sh build.sh  --be                      build Backend without clean
#    sh build.sh  --fe --clean              clean and build Frontend and Spark Dpp application
#    sh build.sh  --fe --be --clean         clean and build Frontend, Spark Dpp application and Backend
#    sh build.sh  --spark-dpp               build Spark DPP application alone
#
# You need to make sure all thirdparty libraries have been
# compiled and installed correctly.
##############################################################

set -eo pipefail

ROOT=`dirname "$0"`
ROOT=`cd "$ROOT"; pwd`
MACHINE_TYPE=$(uname -m)

export STARROCKS_HOME=${ROOT}

. ${STARROCKS_HOME}/env.sh

#build thirdparty libraries if necessary
if [[ ! -f ${STARROCKS_THIRDPARTY}/installed/lib/mariadb/libmariadbclient.a ]]; then
    echo "Thirdparty libraries need to be build ..."
    ${STARROCKS_THIRDPARTY}/build-thirdparty.sh
fi

PARALLEL=$[$(nproc)/4+1]

# Check args
usage() {
  echo "
Usage: $0 <options>
  Optional options:
     --be               build Backend
     --fe               build Frontend and Spark Dpp application
     --spark-dpp        build Spark DPP application
     --clean            clean and build target
     --with-gcov        build Backend with gcov, has an impact on performance
     --without-gcov     build Backend without gcov(default)

  Eg.
    $0                                      build all
    $0 --be                                 build Backend without clean
    $0 --fe --clean                         clean and build Frontend and Spark Dpp application
    $0 --fe --be --clean                    clean and build Frontend, Spark Dpp application and Backend
    $0 --spark-dpp                          build Spark DPP application alone
  "
  exit 1
}

OPTS=$(getopt \
  -n $0 \
  -o '' \
  -o 'h' \
  -l 'be' \
  -l 'fe' \
  -l 'spark-dpp' \
  -l 'clean' \
  -l 'with-gcov' \
  -l 'without-gcov' \
  -l 'help' \
  -- "$@")

if [ $? != 0 ] ; then
    usage
fi

eval set -- "$OPTS"

BUILD_BE=
BUILD_FE=
BUILD_SPARK_DPP=
CLEAN=
RUN_UT=
WITH_GCOV=OFF
if [[ -z ${USE_AVX2} ]]; then
    USE_AVX2=ON
fi
if [[ -z ${USE_SSE4_2} ]]; then
    USE_SSE4_2=ON
fi


HELP=0
if [ $# == 1 ] ; then
    # default
    BUILD_BE=1
    BUILD_FE=1
    BUILD_SPARK_DPP=1
    CLEAN=0
    RUN_UT=0
else
    BUILD_BE=0
    BUILD_FE=0
    BUILD_SPARK_DPP=0
    CLEAN=0
    RUN_UT=0
    while true; do
        case "$1" in
            --be) BUILD_BE=1 ; shift ;;
            --fe) BUILD_FE=1 ; shift ;;
            --spark-dpp) BUILD_SPARK_DPP=1 ; shift ;;
            --clean) CLEAN=1 ; shift ;;
            --ut) RUN_UT=1   ; shift ;;
            --with-gcov) WITH_GCOV=ON; shift ;;
            --without-gcov) WITH_GCOV=OFF; shift ;;
            -h) HELP=1; shift ;;
            --help) HELP=1; shift ;;
            --) shift ;  break ;;
            *) echo "Internal error" ; exit 1 ;;
        esac
    done
fi

if [[ ${HELP} -eq 1 ]]; then
    usage
    exit
fi

if [ ${CLEAN} -eq 1 -a ${BUILD_BE} -eq 0 -a ${BUILD_FE} -eq 0 -a ${BUILD_SPARK_DPP} -eq 0 ]; then
    echo "--clean can not be specified without --fe or --be or --spark-dpp"
    exit 1
fi

echo "Get params:
    BUILD_BE            -- $BUILD_BE
    BUILD_FE            -- $BUILD_FE
    BUILD_SPARK_DPP     -- $BUILD_SPARK_DPP
    CLEAN               -- $CLEAN
    RUN_UT              -- $RUN_UT
    WITH_GCOV           -- $WITH_GCOV
    USE_AVX2            -- $USE_AVX2
"

# Clean and build generated code
echo "Build generated code"
cd ${STARROCKS_HOME}/gensrc
if [ ${CLEAN} -eq 1 ]; then
   make clean
   rm -rf ${STARROCKS_HOME}/fe/fe-core/target
fi
# DO NOT using parallel make(-j) for gensrc
make
cd ${STARROCKS_HOME}


if [[ "${MACHINE_TYPE}" == "aarch64" ]]; then
    export LIBRARY_PATH=${JAVA_HOME}/jre/lib/aarch64/server/
else
    export LIBRARY_PATH=${JAVA_HOME}/jre/lib/amd64/server/
fi


# Clean and build Backend
if [ ${BUILD_BE} -eq 1 ] ; then
    CMAKE_BUILD_TYPE=${BUILD_TYPE:-Release}
    echo "Build Backend: ${CMAKE_BUILD_TYPE}"
    CMAKE_BUILD_DIR=${STARROCKS_HOME}/be/build_${CMAKE_BUILD_TYPE}
    if [ "${WITH_GCOV}" = "ON" ]; then
        CMAKE_BUILD_DIR=${STARROCKS_HOME}/be/build_${CMAKE_BUILD_TYPE}_gcov
    fi
    if [ ${CLEAN} -eq 1 ]; then
        rm -rf $CMAKE_BUILD_DIR
        rm -rf ${STARROCKS_HOME}/be/output/
    fi
    mkdir -p ${CMAKE_BUILD_DIR}
    cd ${CMAKE_BUILD_DIR}
    ${CMAKE_CMD} .. -DSTARROCKS_THIRDPARTY=${STARROCKS_THIRDPARTY} -DSTARROCKS_HOME=${STARROCKS_HOME} -DCMAKE_CXX_COMPILER_LAUNCHER=ccache -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE} \
                    -DMAKE_TEST=OFF -DWITH_GCOV=${WITH_GCOV} -DUSE_AVX2=$USE_AVX2 -DUSE_SSE4_2=$USE_SSE4_2 -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
    time make -j${PARALLEL}
    make install
    cd ${STARROCKS_HOME}
fi

cd ${STARROCKS_HOME}

# Assesmble FE modules
FE_MODULES=
if [ ${BUILD_FE} -eq 1 -o ${BUILD_SPARK_DPP} -eq 1 ]; then
    if [ ${BUILD_SPARK_DPP} -eq 1 ]; then
        FE_MODULES="fe-common,spark-dpp"
    fi
    if [ ${BUILD_FE} -eq 1 ]; then
        FE_MODULES="fe-common,spark-dpp,fe-core"
    fi
fi

# Clean and build Frontend
if [ ${FE_MODULES}x != ""x ]; then
    echo "Build Frontend Modules: $FE_MODULES"
    cd ${STARROCKS_HOME}/fe
    if [ ${CLEAN} -eq 1 ]; then
        ${MVN_CMD} clean
    fi
    ${MVN_CMD} package -pl ${FE_MODULES} -DskipTests
    cd ${STARROCKS_HOME}
fi

# Build JDBC Bridge
echo "Build JDBC Bridge"
cd ${STARROCKS_HOME}/jdbc_bridge
if [ ${CLEAN} -eq 1 ]; then
    ${MVN_CMD} clean
fi
${MVN_CMD} package -DskipTests
cd ${STARROCKS_HOME}


# Clean and prepare output dir
STARROCKS_OUTPUT=${STARROCKS_HOME}/output/
mkdir -p ${STARROCKS_OUTPUT}

# Copy Frontend and Backend
if [ ${BUILD_FE} -eq 1 -o ${BUILD_SPARK_DPP} -eq 1 ]; then
    if [ ${BUILD_FE} -eq 1 ]; then
        install -d ${STARROCKS_OUTPUT}/fe/bin ${STARROCKS_OUTPUT}/fe/conf/ \
                   ${STARROCKS_OUTPUT}/fe/webroot/ ${STARROCKS_OUTPUT}/fe/lib/ \
                   ${STARROCKS_OUTPUT}/fe/spark-dpp/

        cp -r -p ${STARROCKS_HOME}/bin/*_fe.sh ${STARROCKS_OUTPUT}/fe/bin/
        cp -r -p ${STARROCKS_HOME}/bin/show_fe_version.sh ${STARROCKS_OUTPUT}/fe/bin/
        cp -r -p ${STARROCKS_HOME}/bin/common.sh ${STARROCKS_OUTPUT}/fe/bin/
        cp -r -p ${STARROCKS_HOME}/conf/fe.conf ${STARROCKS_OUTPUT}/fe/conf/
        cp -r -p ${STARROCKS_HOME}/conf/hadoop_env.sh ${STARROCKS_OUTPUT}/fe/conf/
        rm -rf ${STARROCKS_OUTPUT}/fe/lib/*
        cp -r -p ${STARROCKS_HOME}/fe/fe-core/target/lib/* ${STARROCKS_OUTPUT}/fe/lib/
        cp -r -p ${STARROCKS_HOME}/fe/fe-core/target/starrocks-fe.jar ${STARROCKS_OUTPUT}/fe/lib/
        cp -r -p ${STARROCKS_HOME}/webroot/* ${STARROCKS_OUTPUT}/fe/webroot/
        cp -r -p ${STARROCKS_HOME}/fe/spark-dpp/target/spark-dpp-*-jar-with-dependencies.jar ${STARROCKS_OUTPUT}/fe/spark-dpp/
        cp -r -p ${STARROCKS_THIRDPARTY}/installed/aliyun_oss_jars/* ${STARROCKS_OUTPUT}/fe/lib/

    elif [ ${BUILD_SPARK_DPP} -eq 1 ]; then
        install -d ${STARROCKS_OUTPUT}/fe/spark-dpp/
        rm -rf ${STARROCKS_OUTPUT}/fe/spark-dpp/*
        cp -r -p ${STARROCKS_HOME}/fe/spark-dpp/target/spark-dpp-*-jar-with-dependencies.jar ${STARROCKS_OUTPUT}/fe/spark-dpp/
    fi
fi

if [ ${BUILD_BE} -eq 1 ]; then
    install -d ${STARROCKS_OUTPUT}/be/bin  \
               ${STARROCKS_OUTPUT}/be/conf \
               ${STARROCKS_OUTPUT}/be/lib/hadoop \
               ${STARROCKS_OUTPUT}/be/lib/jvm \
               ${STARROCKS_OUTPUT}/be/www  \
               ${STARROCKS_OUTPUT}/udf/lib \
               ${STARROCKS_OUTPUT}/udf/include

    cp -r -p ${STARROCKS_HOME}/be/output/bin/* ${STARROCKS_OUTPUT}/be/bin/
    cp -r -p ${STARROCKS_HOME}/be/output/conf/be.conf ${STARROCKS_OUTPUT}/be/conf/
    cp -r -p ${STARROCKS_HOME}/be/output/conf/hadoop_env.sh ${STARROCKS_OUTPUT}/be/conf/
    cp -r -p ${STARROCKS_HOME}/be/output/lib/* ${STARROCKS_OUTPUT}/be/lib/
    cp -r -p ${STARROCKS_HOME}/be/output/www/* ${STARROCKS_OUTPUT}/be/www/
    cp -r -p ${STARROCKS_HOME}/be/output/udf/*.a ${STARROCKS_OUTPUT}/udf/lib/
    cp -r -p ${STARROCKS_HOME}/be/output/udf/include/* ${STARROCKS_OUTPUT}/udf/include/
    cp -r -p ${STARROCKS_HOME}/gensrc/build/gen_java/udf-class-loader.jar ${STARROCKS_OUTPUT}/be/lib
    cp -r -p ${STARROCKS_HOME}/jdbc_bridge/target/starrocks-jdbc-bridge-jar-with-dependencies.jar ${STARROCKS_OUTPUT}/be/lib
    cp -r -p ${STARROCKS_THIRDPARTY}/installed/hadoop/share/hadoop/common ${STARROCKS_OUTPUT}/be/lib/hadoop/
    cp -r -p ${STARROCKS_THIRDPARTY}/installed/hadoop/share/hadoop/hdfs ${STARROCKS_OUTPUT}/be/lib/hadoop/
    cp -r -p ${STARROCKS_THIRDPARTY}/installed/hadoop/lib/native ${STARROCKS_OUTPUT}/be/lib/hadoop/
    # note: do not use oracle jdk to avoid commercial dispute
    if [[ "${MACHINE_TYPE}" == "aarch64" ]]; then
        cp -r -p ${STARROCKS_THIRDPARTY}/installed/open_jdk/jre/lib/aarch64 ${STARROCKS_OUTPUT}/be/lib/jvm/
    else
        cp -r -p ${STARROCKS_THIRDPARTY}/installed/open_jdk/jre/lib/amd64 ${STARROCKS_OUTPUT}/be/lib/jvm/
    fi
    cp -r -p ${STARROCKS_THIRDPARTY}/installed/aliyun_oss_jars/* ${STARROCKS_OUTPUT}/be/lib/hadoop/hdfs/
fi



cp -r -p "${STARROCKS_HOME}/LICENSE.txt" "${STARROCKS_OUTPUT}/LICENSE.txt"
build-support/gen_notice.py "${STARROCKS_HOME}/licenses,${STARROCKS_HOME}/licenses-binary" "${STARROCKS_OUTPUT}/NOTICE.txt" all

echo "***************************************"
echo "Successfully build StarRocks"
echo "***************************************"

if [[ ! -z ${STARROCKS_POST_BUILD_HOOK} ]]; then
    eval ${STARROCKS_POST_BUILD_HOOK}
fi

exit 0

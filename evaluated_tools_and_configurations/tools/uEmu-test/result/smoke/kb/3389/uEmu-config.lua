--[[
This is a bare minimum S2E config file to demonstrate the use of libs2e with PyKVM.
Please refer to the S2E documentation for more details.
This file was automatically generated at 2026-09-28 14:25:38.944232
]]--

s2e = {
    logging = {
        -- Possible values include "all", "debug", "info", "warn" and "none".
        -- See Logging.h in libs2ecore.
        console = "info",
        logLevel = "info",
    },
    -- All the cl::opt options defined in the engine can be tweaked here.
    -- This can be left empty most of the time.
    -- Most of the options can be found in S2EExecutor.cpp and Executor.cpp.
    kleeArgs = {
		"--verbose-on-symbolic-address=true",
		"--verbose-state-switching=true",
		"--verbose-fork-info=true",
		"--print-mode-switch=false",
		"--fork-on-symbolic-address=false",--no self-modifying code and load libs for IoT firmware
		"--suppress-external-warnings=true"
    },
}

--rom start should be equal to vtor
mem = {
	rom = {
		 {0x08000000,0x20000},
	},
	ram = {
		 {0x20000000,0x50000},
	},
}

init = {
   vtor = 134217728,
}

-- Declare empty plugin settings. They will be populated in the rest of
-- the configuration file.
plugins = {}
pluginsConfig = {}

-- Include various convenient functions
dofile('library.lua')




add_plugin("ARMFunctionMonitor")
pluginsConfig.ARMFunctionMonitor = {
	functionParameterNum = 3,
	callerLevel = 3,
}


add_plugin("PeripheralModelLearning")
pluginsConfig.PeripheralModelLearning = {
	useKnowledgeBase = false,
	useFuzzer = false,
	limitSymNum = 100,
	maxT2Size = 8,
	allowNewPhs = true,
	autoModeSwitch = false,
	enableExtendedInterruptMode = "true",
	cacheFileName = "",
	firmwareName = "/home/czs/uEmu-test/firmware/smoke/3389/3389.elf",
}

add_plugin("InvalidStatesDetection")
pluginsConfig.InvalidStatesDetection = {
	usePeripheralCache = false,
	bb_inv1 = 20,
	bb_inv2 = 2000,
	bb_terminate = 30000,
	tbInterval = 1000,
	killPoints = {

	},
	alivePoints = {

	}
}

add_plugin("ExternalInterrupt")
pluginsConfig.ExternalInterrupt ={
	BBScale= 30000,
	disableSystickInterrupt = false,
	disableIrqs = {

	},
	tbInterval = 1000,

}


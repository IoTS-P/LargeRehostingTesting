#include <iostream>

#include <elfio/elfio.hpp>
#include "ELFLoader.h"

using namespace ELFIO;

std::ifstream input;

int main(const int argc, char** argv) {
    if (argc != 3) {
        std::cout << "Usage: " << argv[0] << " <input_file> <output_elf_path>" << std::endl;
        return 0;
    }

    input = std::ifstream(argv[1], std::ios::binary);

    auto loader = ELFLoader(input, argv[2]);
    loader.buildELF();
}
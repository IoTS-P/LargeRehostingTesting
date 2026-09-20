//
// Created by colin on 25-5-21.
//

#ifndef ELFLOADER_H
#define ELFLOADER_H

#include <elfio/elfio.hpp>


struct mem_section {
    unsigned long image_base;
    unsigned long size;
    std::string name;
    unsigned int flag;
    bool is_mapped;
    char* content;
};

using namespace ELFIO;

class ELFLoader {
public:
    explicit ELFLoader(std::ifstream& file, std::string output);

    void buildELF();

private:
    std::string output_path;

    unsigned char word_size{};
    Elf_Half machine{};
    unsigned char endianness{};
    unsigned long entry_point{};
    std::vector<mem_section> sections = {};

    elfio builder;

    std::vector<std::pair<unsigned long, unsigned long>> calculate_segments();
};

#endif //ELFLOADER_H

git submodule update --init --recursive

echo "Patching MaplePile"

git -C MaplePile apply ../maple_pile_dependices_settings.patch

echo "Generating sources for MaplePile"

cd MaplePile

sh gen_sources.sh

cd ../